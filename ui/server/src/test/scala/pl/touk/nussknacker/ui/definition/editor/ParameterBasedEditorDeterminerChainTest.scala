package pl.touk.nussknacker.ui.definition.editor

import java.time._
import java.time.temporal.ChronoUnit

import com.cronutils.model.Cron
import org.scalatest._
import pl.touk.nussknacker.engine.api.definition._
import pl.touk.nussknacker.engine.api.editor.DualEditorMode
import pl.touk.nussknacker.engine.api.process.ParameterConfig
import pl.touk.nussknacker.engine.api.typed.typing.Typed

class ParameterBasedEditorDeterminerChainTest extends FunSuite with Matchers {

  private val fixedValuesEditor = FixedValuesParameterEditor(possibleValues = List(FixedExpressionValue("a", "a")))
  private val stringEditor = StringParameterEditor

  test("determine editor by config") {
    val param = new Parameter("param", Typed[String], classOf[String], Some(stringEditor), validators = List.empty, additionalVariables = Map.empty, branchParam = false)
    val config = ParameterConfig(None, Some(fixedValuesEditor), None, None)

    val determiner = ParameterEditorDeterminerChain(param, config)

    determiner.determineEditor() shouldBe fixedValuesEditor
  }

  test("determine editor by param") {
    val param = new Parameter("param", Typed[String], classOf[String], Some(stringEditor), validators = List.empty, additionalVariables = Map.empty, branchParam = false)
    val config = ParameterConfig.empty

    val determiner = ParameterEditorDeterminerChain(param, config)

    determiner.determineEditor() shouldBe stringEditor
  }

  test("determine editor by type enum") {
    val param = Parameter[JavaSampleEnum]("param")
    val config = ParameterConfig.empty

    val determiner = ParameterEditorDeterminerChain(param, config)

    determiner.determineEditor() shouldBe DualParameterEditor(FixedValuesParameterEditor(List(
      FixedExpressionValue(s"T(${classOf[JavaSampleEnum].getName}).${JavaSampleEnum.FIRST_VALUE.name()}", "first_value"),
      FixedExpressionValue(s"T(${classOf[JavaSampleEnum].getName}).${JavaSampleEnum.SECOND_VALUE.name()}", "second_value")
    )), DualEditorMode.SIMPLE)
  }

  test("determine editor by type LocalDateTime") {
    val param = Parameter[LocalDateTime]("param")
    val config = ParameterConfig.empty

    val determiner = ParameterEditorDeterminerChain(param, config)

    determiner.determineEditor() shouldBe DualParameterEditor(
      simpleEditor = DateTimeParameterEditor,
      defaultMode = DualEditorMode.SIMPLE
    )
  }

  test("determine editor by type LocalDate") {
    val param = Parameter[LocalDate]("param")
    val config = ParameterConfig.empty

    val determiner = ParameterEditorDeterminerChain(param, config)

    determiner.determineEditor() shouldBe DualParameterEditor(
      simpleEditor = DateParameterEditor,
      defaultMode = DualEditorMode.SIMPLE
    )
  }

  test("determine editor by type LocalTime") {
    val param = Parameter[LocalTime]("param")
    val config = ParameterConfig.empty

    val determiner = ParameterEditorDeterminerChain(param, config)

    determiner.determineEditor() shouldBe DualParameterEditor(
      simpleEditor = TimeParameterEditor,
      defaultMode = DualEditorMode.SIMPLE
    )
  }

  test("determine editor by type String") {
    val param = Parameter[String]("param")
    val config = ParameterConfig.empty

    val determiner = ParameterEditorDeterminerChain(param, config)

    determiner.determineEditor() shouldBe DualParameterEditor(
      simpleEditor = StringParameterEditor,
      defaultMode = DualEditorMode.RAW
    )
  }

  test("determine editor by type Duration") {
    val param = Parameter[Duration]("param")
    val config = ParameterConfig.empty

    val determiner = ParameterEditorDeterminerChain(param, config)

    determiner.determineEditor() shouldBe DualParameterEditor(
      simpleEditor = DurationParameterEditor(List(ChronoUnit.DAYS, ChronoUnit.HOURS, ChronoUnit.MINUTES)),
      defaultMode = DualEditorMode.SIMPLE
    )
  }

  test("determine editor by config for Duration") {
    val param = Parameter[Duration]("param")
    val editor = DurationParameterEditor(timeRangeComponents = List(ChronoUnit.MINUTES))
    val config = new ParameterConfig(None, Some(editor), None, None)

    val determiner = ParameterEditorDeterminerChain(param, config)

    determiner.determineEditor() shouldBe editor
  }

  test("determine editor by type Period") {
    val param = Parameter[Period]("param")
    val config = ParameterConfig.empty

    val determiner = ParameterEditorDeterminerChain(param, config)

    determiner.determineEditor() shouldBe DualParameterEditor(
      simpleEditor = PeriodParameterEditor(List(ChronoUnit.YEARS, ChronoUnit.MONTHS, ChronoUnit.DAYS)),
      defaultMode = DualEditorMode.SIMPLE
    )
  }

  test("determine editor by config for Period") {
    val param = Parameter[Period]("param")
    val editor = DurationParameterEditor(timeRangeComponents = List(ChronoUnit.MINUTES))
    val config = new ParameterConfig(None, Some(editor), None, None)

    val determiner = ParameterEditorDeterminerChain(param, config)

    determiner.determineEditor() shouldBe editor
  }

  test("determine editor by type Cron") {
    val param = Parameter[Cron]("param")
    val config = ParameterConfig.empty

    val determiner = ParameterEditorDeterminerChain(param, config)

    determiner.determineEditor() shouldBe DualParameterEditor(
      simpleEditor = CronParameterEditor,
      defaultMode = DualEditorMode.SIMPLE
    )
  }

  test("determine editor by config for Cron") {
    val param = Parameter[Cron]("param")
    val config = ParameterConfig(None, Some(CronParameterEditor), None, None)

    val determiner = ParameterEditorDeterminerChain(param, config)

    determiner.determineEditor() shouldBe CronParameterEditor
  }

  test("determine default editor") {
    val param = Parameter[BigDecimal]("param")
    val config = ParameterConfig.empty

    val determiner = ParameterEditorDeterminerChain(param, config)

    determiner.determineEditor() shouldBe RawParameterEditor
  }
}
