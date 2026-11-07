package ee.schimke.livery.client

import ee.schimke.livery.protocol.ActionSpec
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.InputSpec
import ee.schimke.livery.protocol.Target
import ee.schimke.livery.protocol.TemplateEntry
import ee.schimke.livery.protocol.TemplateEvent
import ee.schimke.livery.protocol.ValueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/** PROTOCOL.md §7: `track` actions and declared arguments, the only arguments events carry. */
class InteractionTest {
  private val contract = Contract(
    "demo/card", 1,
    actions = mapOf(
      "open" to ActionSpec("navigate", args = mapOf("route" to InputSpec(ValueType.String, required = true, maxLength = 20))),
      "expand" to ActionSpec("track", args = mapOf("section" to InputSpec(ValueType.String), "index" to InputSpec(ValueType.Int, min = 0.0, max = 9.0))),
      "rate" to ActionSpec("app:rate", args = mapOf("stars" to InputSpec(ValueType.Int, required = true, min = 1.0, max = 5.0), "liked" to InputSpec(ValueType.Bool))),
    ),
  )
  private val plan = RenderPlan(
    LoadedTemplate(TemplateEntry("ab".repeat(32), 1, "card", "demo/card@1", Target("in-app", 7)), null, byteArrayOf(), LoadedTemplate.Origin.Fresh, contract),
    contract, Bindings.EMPTY, experiments = mapOf("look" to "b"),
  )

  @Test fun theGateChecksDeclaredArguments() {
    assertEquals(ActionCheck(contract.actions["open"], mapOf("route" to "home"), null), ActionGate.check(contract, "open", mapOf("route" to "home", "secret" to "x")))
    assertEquals("missing argument 'route'", ActionGate.check(contract, "open", emptyMap()).problem)
    assertEquals("argument 'route' is longer than 20", ActionGate.check(contract, "open", mapOf("route" to "x".repeat(21))).problem)
    assertEquals("argument 'stars' is above 5.0", ActionGate.check(contract, "rate", mapOf("stars" to "6")).problem)
    assertEquals("argument 'stars' is not an int", ActionGate.check(contract, "rate", mapOf("stars" to "five")).problem)
    assertEquals("argument 'liked' is not a bool", ActionGate.check(contract, "rate", mapOf("stars" to "4", "liked" to "yes")).problem)
    assertEquals("undeclared action", ActionGate.check(contract, "share", emptyMap()).problem)
    assertEquals(listOf("action pick argument tint is color: an action argument is a string, int, long, float or bool"),
      ActionGate.declarationProblems("pick", ActionSpec("track", args = mapOf("tint" to InputSpec(ValueType.Color)))))
  }

  /** PROTOCOL.md §7.1: a handler gets a `set`'s value as the gate passed it, so a document's 1 is `true`. */
  @Test fun aSetsHandlerGetsTheCheckedValue() = runTest {
    val toggle = Contract("demo/toggle", 1, inputs = mapOf("on" to InputSpec(ValueType.Bool)), actions = mapOf("setOn" to ActionSpec("set", input = "on")))
    val p = plan.copy(contract = toggle)
    val events = mutableListOf<TemplateEvent>()
    val handled = mutableListOf<Map<String, String>>()
    val router = ActionRouter(mapOf("set" to { _, _, args -> handled += args }), { false }, EventSink { events += it }, { 5 })
    router.dispatch(p, "setOn", mapOf("value" to "1"))
    router.dispatch(p, "setOn", mapOf("value" to "0"))
    assertEquals(listOf(mapOf("value" to "true"), mapOf("value" to "false")), handled)
    assertEquals(listOf(mapOf("value" to "true"), mapOf("value" to "false")), events.map { it.args }, "and so does its event")
    router.dispatch(p, "setOn", mapOf("value" to "2"))
    assertEquals(2, handled.size, "a value the input doesn't allow reaches no handler")
  }

  @Test fun trackNeedsNoHandlerAndEventsCarryOnlyDeclaredArguments() = runTest {
    val events = mutableListOf<TemplateEvent>()
    val handled = mutableListOf<Pair<String, Map<String, String>>>()
    val router = ActionRouter(mapOf("navigate" to { _, a, args -> handled += a to args }), { false }, EventSink { events += it }, { 5 })

    router.dispatch(plan, "expand", mapOf("section" to "specs", "index" to "2", "note" to "free text"))
    val tracked = events.single()
    assertEquals(TemplateEvent.Type.Action, tracked.type)
    assertEquals(mapOf("index" to "2", "section" to "specs"), tracked.args, "never an undeclared argument")
    assertEquals(mapOf("look" to "b"), tracked.experiments)

    events.clear()
    router.dispatch(plan, "open", mapOf("route" to "home", "ref" to "x"))
    assertEquals(listOf("open" to mapOf("route" to "home", "ref" to "x")), handled, "a handler gets every argument")
    assertEquals(mapOf("route" to "home"), events.single().args)

    events.clear()
    router.dispatch(plan, "open", emptyMap())
    assertEquals(TemplateEvent.Type.Error, events.single().type)
    assertEquals("missing argument 'route'", events.single().detail)
    assertEquals(1, handled.size, "a dropped action reaches no handler")

    events.clear()
    router.dispatch(plan, "rate", mapOf("stars" to "4"))
    assertEquals("no handler for app:rate", events.single().detail)
    assertNull(events.single().durationMs)
  }

  @Test fun aSlotTagNamesThePlacementOrContractAndTheDesign() {
    assertEquals("livery:demo/card@1:card", plan.slotTag)
    val decided = plan.copy(decision = ee.schimke.livery.protocol.Decision("home.top", "d1", ee.schimke.livery.protocol.TemplateRef(designId = "card")))
    assertEquals("livery:home.top:card", decided.slotTag)
  }

  @Test fun analyticsCarriesArgumentsAndDuration() {
    val action = TemplateEvent(TemplateEvent.Type.Action, 1, action = "expand", args = mapOf("section" to "specs", "index" to "2"))
    assertEquals(mapOf("action" to "expand", "arg_index" to "2", "arg_section" to "specs"), AnalyticsEvents.params(action))
    val leave = TemplateEvent(TemplateEvent.Type.Leave, 1, designId = "card", durationMs = 2_500)
    assertEquals("livery_leave", AnalyticsEvents.name(leave.type))
    assertEquals(mapOf("design_id" to "card", "duration_ms" to 2_500L), AnalyticsEvents.params(leave))
  }
}
