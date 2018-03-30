package pl.touk.nussknacker.engine.sql

import java.lang.reflect.Member

import org.scalatest.{FunSuite, Matchers}
import pl.touk.nussknacker.engine.api.typed.typing.Typed
import pl.touk.nussknacker.engine.sql.TypedClassColumnModel.CreateColumnClassExtractionPredicate

import scala.reflect.ClassTag
import SqlType._

class TypedClassColumnModelTest extends FunSuite with Matchers{
  def typeMe[T](implicit classTag: ClassTag[T]): ColumnModel = TypedClassColumnModel.create(Typed[T].asInstanceOf[Typed])
  test("ignore inheritance") {
    trait Countable {
      def value = 1
    }
    case class O(field: Boolean) extends Countable
    val result = typeMe[O]
    result shouldEqual ColumnModel(List(Column("field", Bool)))
  }
  test("ignore methods") {
    case class O(field: Boolean) {
      def method = 1
    }
    val result = typeMe[O]
    result shouldEqual ColumnModel(List(Column("field", Bool)))
  }
  test("use typed list") {
    val result = typeMe[Data1]
    result shouldEqual ColumnModel(List(Column("name", Varchar), Column("value", Numeric)))
  }
  case class Data1(name: String, value: Int)

}

class CreateColumnClassExtractionPredicateTest extends FunSuite with Matchers {

  import CreateColumnClassExtractionPredicateTest._

  val predicate = new CreateColumnClassExtractionPredicate(claz)

  def shouldMatchPredicate(member: Member): Unit =
    test(s"member ${member.getName} should match predicate") {
      predicate.matches(member) shouldBe true
    }

  def shouldNotMatchPredicate(member: Member): Unit =
    test(s"member ${member.getName} should not match predicate") {
      predicate.matches(member) shouldBe false
    }

  shouldNotMatchPredicate(ownField)
  ignore("this")(shouldNotMatchPredicate(simpleField))
  shouldMatchPredicate(ownMethod)
  shouldMatchPredicate(overwrittenMethod)
  shouldMatchPredicate(traitMethod)
  shouldMatchPredicate(equalsMethod)
}

private object CreateColumnClassExtractionPredicateTest {

  val claz: Class[CaseClass] = classOf[CaseClass]
  val ownField: Member = claz.getMethod("ownField")
  val ownMethod: Member = claz.getMethod("ownMethod")
  val overwrittenMethod: Member = claz.getMethod("overwrittenMethod")
  val traitMethod: Member = claz.getMethod("traitMethod")
  val equalsMethod: Member = claz.getMethod("equals", classOf[Object])
  val simpleField: Member = classOf[SimpleClass].getDeclaredField("simpleField")

  trait Trait {
    def traitMethod = 2

    def overwrittenMethod: Int
  }

  class SimpleClass {
    private val simpleField = 1

    def getSimpleField: Int = simpleField
  }

  case class CaseClass(ownField: Int) extends Trait {
    def ownMethod = 1

    override def overwrittenMethod: Int = 1
  }

}

