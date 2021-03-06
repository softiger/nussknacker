package pl.touk.nussknacker.engine.spel.internal

import java.lang.reflect.{Method, Modifier}
import java.util.concurrent.TimeoutException

import cats.data.{State, StateT}
import cats.effect.IO
import org.springframework.expression.{EvaluationContext, PropertyAccessor, TypedValue}
import org.springframework.expression.spel.support.ReflectivePropertyAccessor
import pl.touk.nussknacker.engine.api.dict.DictInstance
import pl.touk.nussknacker.engine.api.exception.NonTransientException
import pl.touk.nussknacker.engine.api.lazyy.{ContextWithLazyValuesProvider, LazyContext, LazyValuesProvider}
import pl.touk.nussknacker.engine.api.typed.TypedMap
import pl.touk.nussknacker.engine.spel.SpelExpressionParser.{LazyContextVariableName, LazyValuesProviderVariableName}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._

object propertyAccessors {

  def configured(): Seq[PropertyAccessor] = {
    //FIXME: configurable timeout...
    val lazyValuesTimeout = 1 minute

    Seq(
      new ReflectivePropertyAccessor(),
      NullPropertyAccessor, //must come before other non-standard ones
      new ScalaLazyPropertyAccessor(lazyValuesTimeout), // must be before scalaPropertyAccessor
      ScalaOptionOrNullPropertyAccessor, // // must be before scalaPropertyAccessor
      ScalaPropertyAccessor,
      StaticPropertyAccessor,
      MapPropertyAccessor,
      TypedMapPropertyAccessor,
      TypedDictInstancePropertyAccessor,
      // it can add performance overhead so it will be better to keep it on the bottom
      MapLikePropertyAccessor
    )
  }

  object NullPropertyAccessor extends PropertyAccessor with ReadOnly {

    override def getSpecificTargetClasses: Array[Class[_]] = null

    override def canRead(context: EvaluationContext, target: Any, name: String): Boolean = target == null

    override def read(context: EvaluationContext, target: Any, name: String): TypedValue =
      //can we extract anything else here?
      throw NonTransientException(name, s"Cannot invoke method/property $name on null object")
  }

  /* PropertyAccessor for case classes
    This one is a bit tricky. We extend ReflectivePropertyAccessor, as it's the only sensible way to make it compilable,
    however it's not so easy to extend and in interpreted mode we skip original implementation
   */
  object ScalaPropertyAccessor extends ReflectivePropertyAccessor with ReadOnly with Caching {

    override def findGetterForProperty(propertyName: String, clazz: Class[_], mustBeStatic: Boolean): Method = {
      findMethodFromClass(propertyName, clazz).orNull
    }

    override protected def reallyFindMethod(name: String, target: Class[_]) : Option[Method] =
      target.getMethods.find(m => m.getParameterCount == 0 && m.getName == name)

    override protected def invokeMethod(propertyName: String, method: Method, target: Any, context: EvaluationContext): AnyRef = {
      method.invoke(target)
    }

    override def getSpecificTargetClasses: Array[Class[_]] = null
  }

  object StaticPropertyAccessor extends PropertyAccessor with ReadOnly with StaticMethodCaching {

    override protected def reallyFindMethod(name: String, target: Class[_]): Option[Method] = {
      target.asInstanceOf[Class[_]].getMethods.find(m =>
        m.getParameterCount == 0 && m.getName == name && Modifier.isStatic(m.getModifiers)
      )
    }

    override protected def invokeMethod(propertyName: String, method: Method, target: Any, context: EvaluationContext): Any = {
      method.invoke(target)
    }

    override def getSpecificTargetClasses: Array[Class[_]] = null
  }

  object ScalaOptionOrNullPropertyAccessor extends PropertyAccessor with ReadOnly with Caching {

    override protected def reallyFindMethod(name: String, target: Class[_]) : Option[Method] = {
      target.getMethods.find(m => m.getParameterCount == 0 && m.getName == name && classOf[Option[_]].isAssignableFrom(m.getReturnType))
    }

    override protected def invokeMethod(propertyName: String, method: Method, target: Any, context: EvaluationContext): Any = {
      method.invoke(target).asInstanceOf[Option[Any]].orNull
    }

    override def getSpecificTargetClasses: Array[Class[_]] = null
  }


  class ScalaLazyPropertyAccessor(lazyValuesTimeout: Duration) extends PropertyAccessor with ReadOnly with Caching {

    override protected def reallyFindMethod(name: String, target: Class[_]) : Option[Method] = {
      target.getMethods.find(
        m => m.getParameterCount == 0 &&
        m.getReturnType == classOf[State[_,_]] &&
        m.getName == name)
    }

    override protected def invokeMethod(propertyName: String, method: Method, target: Any, context: EvaluationContext): Any = {
      val f = method
        .invoke(target)
        .asInstanceOf[StateT[IO, ContextWithLazyValuesProvider, Any]]
      val lazyProvider = context.lookupVariable(LazyValuesProviderVariableName).asInstanceOf[LazyValuesProvider]
      val ctx = context.lookupVariable(LazyContextVariableName).asInstanceOf[LazyContext]
      val futureResult = f.run(ContextWithLazyValuesProvider(ctx, lazyProvider))
      //TODO: async invocation :)
      val (modifiedContext, value) = futureResult.unsafeRunTimed(lazyValuesTimeout)
        .getOrElse(throw new TimeoutException(s"Timout on evaluation ${method.getDeclaringClass}:${method.getName}"))
      context.setVariable(LazyContextVariableName, modifiedContext.context)
      value
    }

    override def getSpecificTargetClasses: Array[Class[_]] = null

  }

  object MapPropertyAccessor extends PropertyAccessor with ReadOnly {

    override def canRead(context: EvaluationContext, target: scala.Any, name: String): Boolean =
      target.asInstanceOf[java.util.Map[_, _]].containsKey(name)

    override def read(context: EvaluationContext, target: scala.Any, name: String) =
      new TypedValue(target.asInstanceOf[java.util.Map[_, _]].get(name))

    override def getSpecificTargetClasses: Array[Class[_]] = Array(classOf[java.util.Map[_, _]])
  }

  object TypedMapPropertyAccessor extends PropertyAccessor with ReadOnly {
    //in theory this always happends, because we typed it properly ;)
    override def canRead(context: EvaluationContext, target: scala.Any, name: String): Boolean =
      target.asInstanceOf[TypedMap].fields.contains(name)

    override def read(context: EvaluationContext, target: scala.Any, name: String) =
      new TypedValue(target.asInstanceOf[TypedMap].fields(name))

    override def getSpecificTargetClasses: Array[Class[_]] = Array(classOf[TypedMap])
  }

  object TypedDictInstancePropertyAccessor extends PropertyAccessor with ReadOnly {
    //in theory this always happends, because we typed it properly ;)
    override def canRead(context: EvaluationContext, target: scala.Any, key: String) =
      true

    // we already replaced dict's label with keys so we can just return value based on key
    override def read(context: EvaluationContext, target: scala.Any, key: String) =
      new TypedValue(target.asInstanceOf[DictInstance].value(key))

    override def getSpecificTargetClasses: Array[Class[_]] = Array(classOf[DictInstance])
  }

  // mainly for avro's GenericRecord purpose
  object MapLikePropertyAccessor extends PropertyAccessor with Caching with ReadOnly {

    override protected def invokeMethod(propertyName: String, method: Method, target: Any, context: EvaluationContext): Any = {
      method.invoke(target, propertyName)
    }

    override protected def reallyFindMethod(name: String, target: Class[_]): Option[Method] = {
      target.getMethods.find(m => m.getName == "get" && (m.getParameterTypes sameElements Array(classOf[String])))
    }

    override def getSpecificTargetClasses: Array[Class[_]] = null
  }

  trait Caching extends CachingBase { self: PropertyAccessor =>

    override def canRead(context: EvaluationContext, target: scala.Any, name: String): Boolean =
      !target.isInstanceOf[Class[_]] && findMethod(name, target).isDefined

    override protected def extractClassFromTarget(target: Any): Option[Class[_]] =
      Option(target).map(_.getClass)
  }

  trait StaticMethodCaching extends CachingBase { self: PropertyAccessor =>
    override def canRead(context: EvaluationContext, target: scala.Any, name: String): Boolean =
      target.isInstanceOf[Class[_]] && findMethod(name, target).isDefined

    override protected def extractClassFromTarget(target: Any): Option[Class[_]] = Option(target).map(_.asInstanceOf[Class[_]])
  }

  trait CachingBase { self: PropertyAccessor =>
    private val methodsCache = new TrieMap[(String, Class[_]), Option[Method]]()

    override def read(context: EvaluationContext, target: scala.Any, name: String): TypedValue =
      findMethod(name, target)
        .map { method =>
          new TypedValue(invokeMethod(name, method, target, context))
        }
        .getOrElse(throw new IllegalAccessException("Property is not readable"))

    protected def findMethod(name: String, target: Any): Option[Method] = {
      //this should *not* happen as we have NullPropertyAccessor
      val targetClass = extractClassFromTarget(target).getOrElse(throw new IllegalArgumentException(s"Null target for $name"))
      findMethodFromClass(name, targetClass)
    }

    protected def findMethodFromClass(name: String, targetClass: Class[_]): Option[Method] = {
      methodsCache.getOrElseUpdate((name, targetClass), reallyFindMethod(name, targetClass))
    }


    protected def extractClassFromTarget(target: Any): Option[Class[_]]
    protected def invokeMethod(propertyName: String, method: Method, target: Any, context: EvaluationContext): Any
    protected def reallyFindMethod(name: String, target: Class[_]) : Option[Method]
  }

  trait ReadOnly { self: PropertyAccessor =>

    override def write(context: EvaluationContext, target: scala.Any, name: String, newValue: scala.Any) =
      throw new IllegalAccessException("Property is not writeable")

    override def canWrite(context: EvaluationContext, target: scala.Any, name: String) = false

  }

}
