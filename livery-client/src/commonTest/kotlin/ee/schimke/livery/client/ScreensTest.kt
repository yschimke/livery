package ee.schimke.livery.client

import ee.schimke.livery.protocol.ActionSpec
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.Decision
import ee.schimke.livery.protocol.Design
import ee.schimke.livery.protocol.FrequencyCap
import ee.schimke.livery.protocol.InputSpec
import ee.schimke.livery.protocol.InstallPolicy
import ee.schimke.livery.protocol.Manifest
import ee.schimke.livery.protocol.PlacementRule
import ee.schimke.livery.protocol.Presentation
import ee.schimke.livery.protocol.Screen
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.Target
import ee.schimke.livery.protocol.TemplateEntry
import ee.schimke.livery.protocol.TemplateEvent
import ee.schimke.livery.protocol.TemplateRef
import ee.schimke.livery.protocol.ValueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** PROTOCOL.md §8.2: interstitials, screens a template opens, one level deep. */
class ScreensTest {
  private val card = Contract(
    "promo/card", 1, inputs = mapOf("title" to InputSpec(ValueType.String)),
    actions = mapOf(
      "details" to ActionSpec("present", placement = "promo.details", args = mapOf("item" to InputSpec(ValueType.String))),
      "close" to ActionSpec("dismiss"),
    ),
  )
  private val details = Contract(
    "promo/details", 1, inputs = mapOf("title" to InputSpec(ValueType.String)),
    actions = mapOf("more" to ActionSpec("present", placement = "promo.more"), "close" to ActionSpec("dismiss")),
  )
  private val inApp = Target(Surfaces.IN_APP, 7, listOf("androidx"))
  private fun screen(p: Presentation) = inApp.copy(container = p.container)
  private fun entry(design: String, contract: String, target: Target, name: String = "$design-${target.container ?: "inline"}"): TemplateEntry {
    val b = Fx.blob(name)
    return TemplateEntry(FakeCrypto.sha(b), b.size.toLong(), design, contract, target)
  }
  private val launch = PlacementRule(
    "launch-promo", "app.launch", TemplateRef(designId = "details-look"), JsonObject(mapOf("title" to JsonPrimitive("New"))),
    frequency = FrequencyCap(maxImpressions = 1, perSeconds = 86_400), presentation = Presentation.Fullscreen,
  )
  private val templates = listOf(
    entry("card-look", "promo/card@1", inApp),
    entry("details-look", "promo/details@1", screen(Presentation.Fullscreen)),
    entry("details-look", "promo/details@1", screen(Presentation.Sheet)),
    // Listed, but its blob isn't on the device.
    entry("missing-look", "promo/details@1", screen(Presentation.Fullscreen), name = "not-on-disk"),
  )
  private fun manifest(rules: List<PlacementRule> = listOf(launch)) = Manifest(
    catalog = "t", serial = 1, generatedAtEpochMs = 0, expiresAtEpochMs = 10_000_000, ttlSeconds = 60,
    contracts = listOf(card, details),
    designs = listOf(
      Design("card-look", "promo/card@1", "Card", install = InstallPolicy.Always),
      Design("details-look", "promo/details@1", "Details", install = InstallPolicy.Always),
      Design("missing-look", "promo/details@1", "Missing", install = InstallPolicy.Always),
    ),
    templates = templates,
    placements = rules,
  )
  private fun repo(m: Manifest) = TemplateRepository(
    StaticSource("https://cdn.example/c"), { _, _ -> FetchResult.Failed(503, "down") }, FakeCrypto, TrustPolicy.AllowUnsigned,
    MemoryBlobStore(), MemoryKeyValueStore(), { 1_000 }, listOf(Fx.inApp),
    TemplateRepository.Bundled(m) { sha -> templates.filter { it.designId != "missing-look" }.firstOrNull { it.sha256 == sha }?.let { Fx.blob("${it.designId}-${it.target.container ?: "inline"}") } },
  )
  private val ctx = DecisionContext(1_000, "android", "en", 1, "u")
  private val placements = listOf(
    ScreenPlacement("app.launch", "promo/details@1", setOf(Presentation.Fullscreen)),
    ScreenPlacement("promo.details", "promo/details@1", setOf(Presentation.Sheet, Presentation.Fullscreen)),
    ScreenPlacement("promo.more", "promo/details@1", setOf(Presentation.Fullscreen)),
  )
  private val caps = Fx.inApp.copy(maxRcApiLevel = 7)

  private fun setup(m: Manifest = manifest(), appData: suspend (String, Map<String, String>) -> JsonObject? = { _, _ -> null }): Triple<LiveryScreens, SlotPlanner, MutableList<TemplateEvent>> {
    val events = mutableListOf<TemplateEvent>()
    val sink = EventSink { events += it }
    val r = repo(m)
    val planner = SlotPlanner(r, sink, { 1_000 })
    val rules = LocalRulesDecider({ m }, MemoryFrequencyStore())
    return Triple(LiveryScreens(planner, rules, { ctx }, placements, { caps }, sink, { 1_000 }, appData), planner, events)
  }

  @Test fun anInterstitialShowsOnceUnderItsCap() = runTest {
    val (screens, _, events) = setup()
    assertTrue(screens.maybeShow("app.launch"))
    val s = screens.stack.value.single()
    assertEquals(Presentation.Fullscreen, s.presentation)
    assertEquals("details-look", s.plan.template.entry.designId)
    assertEquals("screen:fullscreen", s.plan.template.entry.target.container)
    assertFalse(screens.maybeShow("app.launch"), "never over a screen already showing")

    screens.dismiss(s, "system")
    assertEquals(emptyList(), screens.stack.value)
    assertEquals("system", events.single { it.type == TemplateEvent.Type.Dismiss }.detail)
    assertFalse(screens.maybeShow("app.launch"), "its frequency cap counted the showing")
  }

  @Test fun theAppDecidesWhichPresentationsAPlacementAllows() = runTest {
    val (screens, _, events) = setup(manifest(listOf(launch.copy(presentation = Presentation.Sheet))))
    assertFalse(screens.maybeShow("app.launch"))
    assertEquals("sheet isn't allowed for app.launch", events.single().detail)
    assertEquals(TemplateEvent.Type.Fallback, events.single().type)
  }

  @Test fun aScreenOpensOnlyFromWhatIsOnTheDevice() = runTest {
    val (screens, _, _) = setup(manifest(listOf(launch.copy(template = TemplateRef(designId = "missing-look")))))
    assertFalse(screens.maybeShow("app.launch"), "nothing downloaded while the user waits")
    assertEquals(emptyList(), screens.stack.value)
  }

  @Test fun aDecisionShipsItsDetailScreenAndScreensGoOneLevelDeep() = runTest {
    val (screens, planner, events) = setup(appData = { placement, _ -> if (placement == "promo.more") JsonObject(mapOf("title" to JsonPrimitive("More"))) else null })
    val ad = Decision(
      "home.top", "d1", TemplateRef(designId = "card-look"), JsonObject(mapOf("title" to JsonPrimitive("Sale"))),
      experiments = mapOf("copy" to "b"),
      screens = mapOf("details" to Screen(TemplateRef(designId = "details-look"), JsonObject(mapOf("title" to JsonPrimitive("Sale details"))), Presentation.Sheet)),
    )
    val slot = assertIs<PlanResult.Ready>(planner.forDecision(TemplateQuery("promo/card@1", caps), ad)).plan
    assertTrue(screens.present(slot, "details", mapOf("item" to "7")))
    val first = screens.stack.value.single()
    assertEquals(listOf(Presentation.Sheet, 1), listOf(first.presentation, first.depth))
    assertEquals("d1>details", first.plan.decision?.decisionId, "the opened screen is tied to the decision that opened it")
    assertEquals(mapOf("copy" to "b"), first.plan.experiments)

    // One more level: the detail screen opens "more", from the app's data.
    assertTrue(screens.present(first.plan, "more", emptyMap()))
    val second = screens.stack.value.last()
    assertEquals(listOf(Presentation.Fullscreen, 2), listOf(second.presentation, second.depth))
    // No further.
    assertFalse(screens.present(second.plan, "more", emptyMap()))
    assertEquals("a screen opened from a screen can't open another", events.last().detail)

    // Closing the first closes the one it opened, top first.
    screens.dismiss(first, "action")
    assertEquals(emptyList(), screens.stack.value)
    assertEquals(listOf("promo.more", "promo.details"), events.filter { it.type == TemplateEvent.Type.Dismiss }.map { it.placement })
  }

  @Test fun dismissClosesTheScreenItWasTappedIn() = runTest {
    val (screens, planner, _) = setup()
    assertTrue(screens.maybeShow("app.launch"))
    val inline = mutableListOf<String>()
    val router = ActionRouter(screens.handlers { _, a, _ -> inline += a }, { false }, EventSink { }, { 1 })
    router.dispatch(screens.stack.value.single().plan, "close", emptyMap())
    assertEquals(emptyList(), screens.stack.value)
    val slot = assertIs<PlanResult.Ready>(planner.forDecision(TemplateQuery("promo/card@1", caps), Decision("home.top", "d2", TemplateRef(designId = "card-look")))).plan
    router.dispatch(slot, "close", emptyMap())
    assertEquals(listOf("close"), inline, "a slot's dismiss is the app's")
  }

  @Test fun screenTargetsArePrefetched() {
    assertEquals(listOf("screen:sheet", "screen:fullscreen"), LiveryScreens.targets(placements) { caps }.map { it.container })
  }

  @Test fun rules() {
    val p = placements[1]
    assertEquals(ScreenRules.Outcome(Presentation.Sheet, null), ScreenRules.presentation(p, p.placement, Presentation.Inline, 1))
    assertEquals(ScreenRules.Outcome(Presentation.Fullscreen, null), ScreenRules.presentation(p, p.placement, Presentation.Fullscreen, 2))
    assertEquals("dialog isn't allowed for promo.details", ScreenRules.presentation(p, p.placement, Presentation.Dialog, 1).problem)
    assertEquals("a screen opened from a screen can't open another", ScreenRules.presentation(p, p.placement, Presentation.Sheet, 3).problem)
    assertEquals("home.top isn't a screen placement", ScreenRules.presentation(null, "home.top", Presentation.Sheet, 1).problem)
  }
}
