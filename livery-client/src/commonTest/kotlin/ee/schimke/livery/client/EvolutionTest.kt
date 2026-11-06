package ee.schimke.livery.client

import ee.schimke.livery.protocol.ActionSpec
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.InputSpec
import ee.schimke.livery.protocol.ListSpec
import ee.schimke.livery.protocol.ValueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvolutionTest {
  private val v1 = Contract(
    "c/card", 1, minor = 1,
    inputs = mapOf(
      "title" to InputSpec(ValueType.String, required = true, maxLength = 20),
      "count" to InputSpec(ValueType.Int, min = 0.0, max = 10.0),
    ),
    lists = mapOf("rows" to ListSpec(mapOf("label" to InputSpec(ValueType.String)), max = 3)),
    actions = mapOf("open" to ActionSpec("navigate")),
  )

  private fun breaking(new: Contract) = ContractEvolution.compare(v1, new).filter { it.breaking }.map { it.path }
  private fun compatible(new: Contract) = ContractEvolution.compare(v1, new).filterNot { it.breaking }.map { it.path }

  @Test fun sameContractHasNoChanges() = assertEquals(emptyList(), ContractEvolution.compare(v1, v1))

  @Test fun optionalAdditionsMarkedWithSinceAreMinor() {
    val v2 = v1.copy(
      minor = 2,
      inputs = v1.inputs + ("subtitle" to InputSpec(ValueType.String, since = 2)),
      lists = v1.lists + ("chips" to ListSpec(mapOf("text" to InputSpec(ValueType.String, required = true)), max = 2, since = 2)),
      actions = v1.actions + ("share" to ActionSpec("share", since = 2)),
    )
    assertEquals(emptyList(), breaking(v2))
    assertEquals(listOf("subtitle", "lists.chips", "actions.share"), compatible(v2))
    assertEquals(emptyList(), ContractEvolution.problems(v2))
  }

  @Test fun anAdditionWithoutSinceBreaksOldApps() {
    assertEquals(listOf("subtitle"), breaking(v1.copy(minor = 2, inputs = v1.inputs + ("subtitle" to InputSpec(ValueType.String)))))
    assertEquals(listOf("actions.share"), breaking(v1.copy(minor = 2, actions = v1.actions + ("share" to ActionSpec("share")))))
  }

  @Test fun requiredAdditionsRemovalsAndTypeChangesNeedAMajor() {
    assertEquals(listOf("subtitle"), breaking(v1.copy(minor = 2, inputs = v1.inputs + ("subtitle" to InputSpec(ValueType.String, required = true, since = 2)))))
    assertEquals(listOf("count"), breaking(v1.copy(inputs = v1.inputs - "count")))
    assertEquals(listOf("count"), breaking(v1.copy(inputs = v1.inputs + ("count" to InputSpec(ValueType.Float, min = 0.0, max = 10.0)))))
    assertEquals(listOf("count"), breaking(v1.copy(inputs = v1.inputs + ("count" to InputSpec(ValueType.Int, required = true, min = 0.0, max = 10.0)))))
    assertEquals(listOf("actions.open"), breaking(v1.copy(actions = mapOf("open" to ActionSpec("open-url")))))
    assertEquals(listOf("lists.rows.label"), breaking(v1.copy(lists = mapOf("rows" to ListSpec(mapOf("label" to InputSpec(ValueType.Int)), max = 3)))))
  }

  @Test fun tighteningBreaksProducersAndLooseningDoesNot() {
    val tighter = v1.copy(inputs = v1.inputs + ("title" to InputSpec(ValueType.String, required = true, maxLength = 10)))
    assertEquals(listOf("title"), breaking(tighter))
    val looser = v1.copy(minor = 2, inputs = v1.inputs + ("count" to InputSpec(ValueType.Int, min = -5.0)))
    assertEquals(emptyList(), breaking(looser))
    assertEquals(listOf("count", "count"), compatible(looser))
    assertEquals(listOf("lists.rows"), breaking(v1.copy(lists = mapOf("rows" to ListSpec(mapOf("label" to InputSpec(ValueType.String)), max = 2)))))
  }

  @Test fun relaxingRequiredIsCompatible() {
    val v2 = v1.copy(minor = 2, inputs = v1.inputs + ("title" to InputSpec(ValueType.String, maxLength = 20)))
    assertEquals(emptyList(), breaking(v2))
  }

  @Test fun sinceAboveTheMinorOrOnARequiredInputIsAProblem() {
    val bad = v1.copy(inputs = v1.inputs + ("later" to InputSpec(ValueType.String, since = 3)) + ("late" to InputSpec(ValueType.String, since = 1, required = true)))
    val problems = ContractEvolution.problems(bad)
    assertEquals(2, problems.size, problems.toString())
    assertTrue(problems.any { it.startsWith("later:") } && problems.any { it.startsWith("late:") })
  }

  @Test fun aTemplateRequiresTheNewestMinorItUses() {
    val v3 = v1.copy(
      minor = 3,
      inputs = v1.inputs + ("subtitle" to InputSpec(ValueType.String, since = 2)),
      lists = mapOf("rows" to ListSpec(mapOf("label" to InputSpec(ValueType.String), "icon" to InputSpec(ValueType.Image, since = 3)), max = 3)),
      actions = v1.actions + ("share" to ActionSpec("share", since = 2)),
    )
    assertEquals(0, ContractEvolution.requiresMinor(v3, listOf("title", "rows.count", "rows.0.label", "theme.primary"), listOf("open")))
    assertEquals(2, ContractEvolution.requiresMinor(v3, listOf("title", "subtitle"), listOf("open")))
    assertEquals(2, ContractEvolution.requiresMinor(v3, listOf("title"), listOf("share")))
    assertEquals(3, ContractEvolution.requiresMinor(v3, listOf("rows.1.icon"), emptyList()))
  }

  @Test fun actionArgsFollowTheInputRules() {
    val withArg = v1.copy(actions = mapOf("open" to ActionSpec("navigate", args = mapOf("route" to InputSpec(ValueType.String)))))
    fun arg(spec: InputSpec) = withArg.copy(minor = 2, actions = mapOf("open" to ActionSpec("navigate", args = mapOf("route" to spec))))
    fun breakingFrom(new: Contract) = ContractEvolution.compare(withArg, new).filter { it.breaking }.map { it.path }
    assertEquals(listOf("actions.open.args.route"), breakingFrom(arg(InputSpec(ValueType.String, required = true))))
    assertEquals(listOf("actions.open.args.route"), breakingFrom(arg(InputSpec(ValueType.String, maxLength = 10))))
    assertEquals(listOf("actions.open.args.route"), breakingFrom(arg(InputSpec(ValueType.Int))))
    val optionalArg = withArg.copy(minor = 2, actions = mapOf("open" to ActionSpec("navigate", args = withArg.actions.getValue("open").args + ("tab" to InputSpec(ValueType.String, since = 2)))))
    assertEquals(emptyList(), breakingFrom(optionalArg))
  }

  @Test fun actionArgsAreCheckedOnTheirOwn() {
    val c = v1.copy(actions = mapOf("open" to ActionSpec("navigate", args = mapOf(
      "later" to InputSpec(ValueType.String, since = 2),
      "late" to InputSpec(ValueType.String, since = 1, required = true),
    ))))
    val problems = ContractEvolution.problems(c)
    assertEquals(2, problems.size, problems.toString())
    assertTrue(problems.any { it.startsWith("actions.open.args.later:") } && problems.any { it.startsWith("actions.open.args.late:") })
    // An action added with its required args in the same minor is fine.
    val fresh = v1.copy(minor = 2, actions = mapOf("share" to ActionSpec("share", since = 2, args = mapOf("to" to InputSpec(ValueType.String, required = true, since = 2)))))
    assertEquals(emptyList(), ContractEvolution.problems(fresh))
  }
}
