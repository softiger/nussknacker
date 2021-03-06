package pl.touk.nussknacker.engine.spel

import java.lang.reflect.{Method, Modifier}
import java.time.{LocalDate, LocalDateTime}
import java.util
import java.util.concurrent.TimeoutException

import cats.data.Validated.Valid
import cats.data.{NonEmptyList, State, StateT, Validated}
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.lang3.StringUtils
import org.springframework.expression._
import org.springframework.expression.common.{CompositeStringExpression, LiteralExpression}
import org.springframework.expression.spel.ast.SpelNodeImpl
import org.springframework.expression.spel.support.ReflectivePropertyAccessor
import org.springframework.expression.spel.{SpelCompilerMode, SpelEvaluationException, SpelParserConfiguration, standard}
import pl.touk.nussknacker.engine.api
import pl.touk.nussknacker.engine.api.Context
import pl.touk.nussknacker.engine.api.context.ValidationContext
import pl.touk.nussknacker.engine.api.dict.{DictInstance, DictRegistry}
import pl.touk.nussknacker.engine.api.exception.NonTransientException
import pl.touk.nussknacker.engine.api.expression.{ExpressionParseError, ExpressionParser, TypedExpression, ValueWithLazyContext}
import pl.touk.nussknacker.engine.api.lazyy.{ContextWithLazyValuesProvider, LazyContext, LazyValuesProvider}
import pl.touk.nussknacker.engine.api.process.ClassExtractionSettings
import pl.touk.nussknacker.engine.api.typed.TypedMap
import pl.touk.nussknacker.engine.api.typed.supertype.{CommonSupertypeFinder, SupertypeClassResolutionStrategy}
import pl.touk.nussknacker.engine.api.typed.typing.{SingleTypingResult, TypingResult, Unknown}
import pl.touk.nussknacker.engine.dict.{KeysDictTyper, LabelsDictTyper}
import pl.touk.nussknacker.engine.expression.NullExpression
import pl.touk.nussknacker.engine.functionUtils.CollectionUtils
import pl.touk.nussknacker.engine.spel.SpelExpressionParser.Flavour
import pl.touk.nussknacker.engine.spel.internal.EvaluationContextPreparer

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
  * Workaround for Spel compilation problem when expression's underlying class changes.
  * Spel tries to explicitly cast result of compiled expression to a class that has been cached during the compilation.
  *
  * Problematic scenario:
  * In case compilation occurs with type ArrayList and during evaulation a List is provided ClassCastException is thrown.
  * Workaround:
  * In such case we try to parse and compile expression again.
  *
  * Possible problems:
  * - unless Expression is marked @volatile multiple threads might parse it on their own,
  * - performance problem might occur if the ClassCastException is thrown often (e. g. for consecutive calls to getValue)
  */
final case class ParsedSpelExpression(original: String, parser: () => Validated[NonEmptyList[ExpressionParseError], Expression], initial: Expression) extends LazyLogging {
  @volatile var parsed: Expression = initial

  def getValue[T](context: EvaluationContext, desiredResultType: Class[_]): T = {
    def value(): T = parsed.getValue(context, desiredResultType).asInstanceOf[T]

    try {
      value()
    } catch {
      case e: SpelEvaluationException if Option(e.getCause).exists(_.isInstanceOf[ClassCastException]) =>
        logger.warn("Error during expression evaluation '{}': {}. Trying to compile", original, e.getMessage)
        forceParse()
        value()
    }
  }

  def forceParse(): Unit = {
    //we already parsed this expression successfully, so reparsing should NOT fail
    parsed = parser().getOrElse(throw new RuntimeException(s"Failed to reparse $original - this should not happen!"))
  }
}

class SpelExpression(parsed: ParsedSpelExpression,
                     expectedReturnType: TypingResult,
                     flavour: Flavour,
                     evaluationContextPreparer: EvaluationContextPreparer) extends api.expression.Expression with LazyLogging {

  import pl.touk.nussknacker.engine.spel.SpelExpressionParser._

  override val original: String = parsed.original

  override val language: String = flavour.languageId

  private val expectedClass =
    expectedReturnType match {
      case r: SingleTypingResult =>
        r.objType.klass
      case _ =>
        // TOOD: what should happen here?
        classOf[Any]
    }

  // TODO: better interoperability with scala type, mainly: scala.math.BigDecimal, scala.math.BigInt and collections
  override def evaluate[T](ctx: Context,
                           lazyValuesProvider: LazyValuesProvider): Future[ValueWithLazyContext[T]] = logOnException(ctx) {
    if (expectedClass == classOf[SpelExpressionRepr]) {
      return Future.successful(ValueWithLazyContext(SpelExpressionRepr(parsed.parsed, ctx, original).asInstanceOf[T], ctx.lazyContext))
    }

    val evaluationContext = evaluationContextPreparer.prepareEvaluationContext(ctx, lazyValuesProvider)

    //TODO: async evaluation of lazy vals...
    val value = parsed.getValue[T](evaluationContext, expectedClass)
    val modifiedLazyContext = evaluationContext.lookupVariable(LazyContextVariableName).asInstanceOf[LazyContext]
    Future.successful(ValueWithLazyContext(value, modifiedLazyContext))
  }

  private def logOnException[A](ctx: Context)(block: => A): A = {
    try {
      block
    } catch {
      case NonFatal(e) =>
        logger.info(s"Expression evaluation failed. Original {}, ctxId: {}, message: {}", original, ctx.id, e.getMessage)
        //we log twice here because LazyLogging cannot print context and stacktrace at the same time
        logger.debug("Expression evaluation failed. Original: {}. Context: {}", original, ctx)
        logger.debug("Expression evaluation failed", e)
        throw e
    }
  }
}

class SpelExpressionParser(parser: org.springframework.expression.spel.standard.SpelExpressionParser,
                           validator: SpelExpressionValidator,
                           dictRegistry: DictRegistry,
                           enableSpelForceCompile: Boolean,
                           flavour: Flavour,
                           prepareEvaluationContext: EvaluationContextPreparer) extends ExpressionParser {

  import pl.touk.nussknacker.engine.spel.SpelExpressionParser._

  override final val languageId: String = flavour.languageId

  override def parseWithoutContextValidation(original: String): Validated[NonEmptyList[ExpressionParseError], api.expression.Expression] = {
    if (shouldUseNullExpression(original)) {
      // This will work only with @Nullable for now because for Option/Optional is needed expectedType
      Valid(NullExpression(original, Unknown, flavour))
    } else {
      baseParse(original).map { parsed =>
        expression(ParsedSpelExpression(original, () => baseParse(original), parsed), Unknown)
      }
    }
  }

  override def parse(original: String, ctx: ValidationContext, expectedType: TypingResult): Validated[NonEmptyList[ExpressionParseError], TypedExpression] = {
    if (shouldUseNullExpression(original)) {
      Valid(TypedExpression(
        NullExpression(original, expectedType, flavour),
        expectedType,
        SpelExpressionTypingInfo(Map.empty)
      ))
    } else {
      baseParse(original).andThen { parsed =>
        validator.validate(parsed, ctx, expectedType).map((_, parsed))
      }.map { case (combinedResult, parsed) =>
        TypedExpression(expression(ParsedSpelExpression(original, () => baseParse(original), parsed), expectedType), combinedResult.finalResult, combinedResult.typingInfo)
      }
    }
  }

  private def shouldUseNullExpression(original: String): Boolean
    = flavour != Template && StringUtils.isBlank(original)

  private def baseParse(original: String): Validated[NonEmptyList[ExpressionParseError], Expression] = {
    Validated.catchNonFatal(parser.parseExpression(original, flavour.parserContext.orNull)).leftMap(ex => NonEmptyList.of(ExpressionParseError(ex.getMessage)))
  }

  private def expression(expression: ParsedSpelExpression, expectedType: TypingResult) = {
    if (enableSpelForceCompile) {
      forceCompile(expression.parsed)
    }
    new SpelExpression(expression, expectedType, flavour, prepareEvaluationContext)
  }

  def typingDictLabels =
    new SpelExpressionParser(parser, validator.withTyper(_.withDictTyper(new LabelsDictTyper(dictRegistry))), dictRegistry, enableSpelForceCompile, flavour, prepareEvaluationContext)

}

object SpelExpressionParser extends LazyLogging {

  sealed abstract class Flavour(val languageId: String, val parserContext: Option[ParserContext])
  object Standard extends Flavour("spel", None)
  //TODO: should we enable other prefixes/suffixes?
  object Template extends Flavour("spelTemplate", Some(ParserContext.TEMPLATE_EXPRESSION))

  private[spel] final val LazyValuesProviderVariableName: String = "$lazy"
  private[spel] final val LazyContextVariableName: String = "$lazyContext"

  //TODO
  //this does not work in every situation - e.g expression (#variable != '') is not compiled
  //maybe we could remove it altogether with "enableSpelForceCompile" flag after some investigation
  private[spel] def forceCompile(parsed: Expression): Unit = {
    parsed match {
      case e:standard.SpelExpression => forceCompile(e)
      case e:CompositeStringExpression => e.getExpressions.foreach(forceCompile)
      case _:LiteralExpression =>
      case _:NullExpression =>
    }
  }

  private def forceCompile(spel: standard.SpelExpression): Unit = {
    val managedToCompile = spel.compileExpression()
    if (!managedToCompile) {
      spel.getAST match {
        case node: SpelNodeImpl if node.isCompilable =>
          throw new IllegalStateException(s"Failed to compile expression: ${spel.getExpressionString}")
        case _ => logger.debug(s"Expression ${spel.getExpressionString} will not be compiled")
      }
    } else {
      logger.debug(s"Compiled ${spel.getExpressionString} with compiler result: $spel")
    }
  }


  //caching?
  def default(classLoader: ClassLoader,
              dictRegistry: DictRegistry,
              enableSpelForceCompile: Boolean,
              strictTypeChecking: Boolean,
              imports: List[String],
              flavour: Flavour,
              strictMethodsChecking: Boolean)
             (implicit classExtractionSettings: ClassExtractionSettings): SpelExpressionParser = {
    val functions = Map(
      "today" -> classOf[LocalDate].getDeclaredMethod("now"),
      "now" -> classOf[LocalDateTime].getDeclaredMethod("now"),
      "distinct" -> classOf[CollectionUtils].getDeclaredMethod("distinct", classOf[util.Collection[_]]),
      "sum" -> classOf[CollectionUtils].getDeclaredMethod("sum", classOf[util.Collection[_]])
    )
    val parser = new org.springframework.expression.spel.standard.SpelExpressionParser(
      //we have to pass classloader, because default contextClassLoader can be sth different than we expect...
      new SpelParserConfiguration(SpelCompilerMode.IMMEDIATE, classLoader)
    )
    val propertyAccessors = internal.propertyAccessors.configured()

    val classResolutionStrategy = if (strictTypeChecking) SupertypeClassResolutionStrategy.Intersection else SupertypeClassResolutionStrategy.Union
    val commonSupertypeFinder = new CommonSupertypeFinder(classResolutionStrategy, strictTypeChecking)
    val validator = new SpelExpressionValidator(new Typer(classLoader, commonSupertypeFinder, new KeysDictTyper(dictRegistry), strictMethodsChecking))
    val evaluationContextPreparer = new EvaluationContextPreparer(classLoader, imports, propertyAccessors, functions)
    new SpelExpressionParser(parser, validator, dictRegistry, enableSpelForceCompile, flavour, evaluationContextPreparer)
  }

}
