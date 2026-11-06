package ee.schimke.livery.client

import ee.schimke.livery.protocol.Arm
import ee.schimke.livery.protocol.ArmRef
import ee.schimke.livery.protocol.Conditions
import ee.schimke.livery.protocol.Experiment
import ee.schimke.livery.protocol.FrequencyCap
import ee.schimke.livery.protocol.PlacementRule
import ee.schimke.livery.protocol.TemplateEvent
import ee.schimke.livery.protocol.TemplateRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject

class ExperimentsTest {
  private fun ctx(id: String, segments: Set<String> = emptySet()) =
    DecisionContext(nowEpochMs = 1_000, platform = "android", locale = "en-GB", appVersion = 10, bucketingId = id, segments = segments)

  private val ab = Experiment("tile-look", listOf(Arm("control", 1), Arm("retro", 1, designs = mapOf("live/status-tile@1" to "tile-retro"))))

  @Test fun bucketingIsStickyAndSplitsByWeight() {
    val users = (0 until 4_000).map { "user-$it" }
    val arms = users.map { LocalBucketing.assignNow(ab, ctx(it)) }
    assertEquals(arms, users.map { LocalBucketing.assignNow(ab, ctx(it)) }, "sticky")
    val retro = arms.count { it == "retro" }
    assertTrue(retro in 1_800..2_200, "about half: $retro")
    val weighted = ab.copy(arms = listOf(Arm("control", 3), Arm("retro", 1)))
    val few = users.count { LocalBucketing.assignNow(weighted, ctx(it)) == "retro" }
    assertTrue(few in 850..1_150, "about a quarter: $few")
  }

  @Test fun allocationOnlyAddsUsers() {
    val users = (0 until 2_000).map { "user-$it" }
    val tenth = users.associateWith { LocalBucketing.assignNow(ab.copy(allocationBasisPoints = 1_000), ctx(it)) }
    val half = users.associateWith { LocalBucketing.assignNow(ab.copy(allocationBasisPoints = 5_000), ctx(it)) }
    assertTrue(tenth.values.count { it != null } in 150..250)
    for ((u, arm) in tenth) if (arm != null) assertTrue(half[u] != null, "$u left the experiment when it grew")
  }

  @Test fun scheduleAndConditionsGateEnrolment() {
    assertNull(LocalBucketing.assignNow(ab.copy(startEpochMs = 2_000), ctx("u")))
    assertNull(LocalBucketing.assignNow(ab.copy(endEpochMs = 1_000), ctx("u")))
    assertNull(LocalBucketing.assignNow(ab.copy(conditions = Conditions(segments = setOf("beta"))), ctx("u")))
    assertTrue(LocalBucketing.assignNow(ab.copy(conditions = Conditions(segments = setOf("beta"))), ctx("u", setOf("beta"))) != null)
  }

  @Test fun aRemoteAssignerWinsAndUnknownArmsFallThrough() = runTest {
    val remote = ExperimentAssigner { e, _ -> if (e.id == "tile-look") "retro" else null }
    assertEquals("retro", FirstAssigner(remote, LocalBucketing).assign(ab, ctx("u")))
    val stale = ExperimentAssigner { _, _ -> "gone" }
    assertEquals(LocalBucketing.assignNow(ab, ctx("u")), FirstAssigner(stale, LocalBucketing).assign(ab, ctx("u")))
    val broken = ExperimentAssigner { _, _ -> error("not fetched") }
    assertEquals(LocalBucketing.assignNow(ab, ctx("u")), FirstAssigner(broken, LocalBucketing).assign(ab, ctx("u")))
  }

  private fun repo(m: ee.schimke.livery.protocol.Manifest) = TemplateRepository(
    StaticSource("https://cdn.example/c"), { _, _ -> FetchResult.Failed(503, "down") }, FakeCrypto, TrustPolicy.AllowUnsigned,
    MemoryBlobStore(), MemoryKeyValueStore(), { 1_000 }, listOf(Fx.inApp),
    TemplateRepository.Bundled(m) { sha -> listOf("default-v7", "retro-v7").map(Fx::blob).firstOrNull { FakeCrypto.sha(it) == sha } },
  )

  @Test fun anArmPicksTheDesignAndIsExposedOnce() = runTest {
    val m = Fx.manifest().copy(experiments = listOf(ab))
    val events = mutableListOf<TemplateEvent>()
    val sink = EventSink { events += it }
    val q = TemplateQuery("live/status-tile@1", Fx.inApp.copy(maxRcApiLevel = 7))
    val data = JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive("Hall")))
    fun planner(arm: String) = SlotPlanner(repo(m), sink, { 1_000 }, Experiments({ m }, { ctx("u") }, { _, _ -> arm }, sink))

    val retro = planner("retro")
    val p = assertIs<PlanResult.Ready>(retro.forAppData(q, data)).plan
    assertEquals("tile-retro", p.template.entry.designId)
    assertEquals(mapOf("tile-look" to "retro"), p.experiments)
    retro.forAppData(q, data)
    assertEquals(1, events.count { it.type == TemplateEvent.Type.Exposure }, "one exposure per arm")
    assertEquals(mapOf("tile-look" to "retro"), events.single { it.type == TemplateEvent.Type.Exposure }.experiments)

    // The control arm picks no design: the usual choice, and no exposure from the design.
    events.clear()
    val control = assertIs<PlanResult.Ready>(planner("control").forAppData(q, data)).plan
    assertEquals("tile-default", control.template.entry.designId)
    assertTrue(events.none { it.type == TemplateEvent.Type.Exposure })

    // A slot that names its design keeps it.
    assertEquals("tile-default", assertIs<PlanResult.Ready>(planner("retro").forAppData(q.copy(designId = "tile-default"), data)).plan.template.entry.designId)
  }

  @Test fun placementRulesCanTargetAnArm() = runTest {
    val promo = Experiment("promo-copy", listOf(Arm("a"), Arm("b")))
    val m = Fx.manifest().copy(
      experiments = listOf(promo),
      placements = listOf(
        PlacementRule("copy-a", "home.top", TemplateRef(designId = "banner"), arm = ArmRef("promo-copy", "a")),
        PlacementRule("copy-b", "home.top", TemplateRef(designId = "banner"), arm = ArmRef("promo-copy", "b")),
      ),
    )
    val forA = LocalRulesDecider({ m }, MemoryFrequencyStore(), { _, _ -> "a" }).decide("home.top", ctx("u"))!!
    assertEquals("local:copy-a", forA.decisionId)
    assertEquals(mapOf("promo-copy" to "a"), forA.experiments)
    assertEquals("local:copy-b", LocalRulesDecider({ m }, MemoryFrequencyStore(), { _, _ -> "b" }).decide("home.top", ctx("u"))?.decisionId)
    assertNull(LocalRulesDecider({ m }, MemoryFrequencyStore(), { _, _ -> null }).decide("home.top", ctx("u")), "not enrolled: neither")
    val ended = m.copy(experiments = listOf(promo.copy(endEpochMs = 500)))
    assertNull(LocalRulesDecider({ ended }, MemoryFrequencyStore(), { _, _ -> "a" }).decide("home.top", ctx("u")), "ended: neither")
  }

  @Test fun analyticsShape() {
    val e = TemplateEvent(
      TemplateEvent.Type.Action, 1, "home.top", "local:copy-a", designId = "banner", sha256 = "ab".repeat(32), action = "cta",
      detail = "x".repeat(300), experiments = mapOf("promo-copy" to "a", "9 lives" to "b"),
    )
    assertEquals("livery_action", AnalyticsEvents.name(e.type))
    assertEquals(
      mapOf("placement" to "home.top", "decision_id" to "local:copy-a", "design_id" to "banner", "template" to "abababababab", "action" to "cta", "detail" to "x".repeat(100), "exp_9_lives" to "b", "exp_promo_copy" to "a"),
      AnalyticsEvents.params(e),
    )
    val logged = mutableListOf<String>()
    val sink = AnalyticsEventSink(AnalyticsSink { _, _ -> error("down") }, AnalyticsSink { name, _ -> logged += name })
    eventSinks(sink, EventSink { error("also down") }).emit(e)
    assertEquals(listOf("livery_action"), logged, "one failing pipeline doesn't stop another")
  }

  /** A backend shared by two devices, like Firestore would be. */
  private class SharedBackend : FrequencyBackend {
    val stored = mutableListOf<ImpressionRecord>()
    var down = false
    override suspend fun push(records: List<ImpressionRecord>) { if (down) error("offline"); stored += records }
    override suspend fun pull(sinceEpochMs: Long): List<ImpressionRecord> { if (down) error("offline"); return stored.filter { it.atEpochMs >= sinceEpochMs } }
  }

  @Test fun aCapHoldsAcrossDevicesAfterASync() = runTest {
    var now = 10_000L
    val backend = SharedBackend()
    val phone = SyncedFrequencyStore(MemoryKeyValueStore(), backend, "phone", { now })
    val tablet = SyncedFrequencyStore(MemoryKeyValueStore(), backend, "tablet", { now })
    val m = Fx.manifest().copy(placements = listOf(PlacementRule("nudge", "home.top", TemplateRef(designId = "banner"), frequency = FrequencyCap(2, 3600))))
    val c = ctx("u").copy(nowEpochMs = now)

    val onPhone = LocalRulesDecider({ m }, phone)
    repeat(2) { onPhone.recordImpression(onPhone.decide("home.top", c)!!, now) }
    assertNull(onPhone.decide("home.top", c))

    val onTablet = LocalRulesDecider({ m }, tablet)
    assertTrue(onTablet.decide("home.top", c) != null, "before syncing, the tablet hasn't heard")
    assertTrue(phone.sync()); assertTrue(tablet.sync())
    assertNull(onTablet.decide("home.top", c), "after syncing, the cap holds on the tablet too")
    assertEquals(2, tablet.impressions("nudge", 0), "each impression once")
    assertTrue(phone.sync()); assertEquals(2, phone.impressions("nudge", 0), "pulling its own back doesn't double them")

    // Offline: impressions stay queued and go up on the next sync that works.
    backend.down = true
    now += 1
    tablet.record("nudge", now)
    assertFalse(tablet.sync())
    backend.down = false
    assertTrue(tablet.sync()); assertTrue(phone.sync())
    assertEquals(3, phone.impressions("nudge", 0))
  }

  @Test fun theLocalStoreSurvivesARestart() = runTest {
    val kv = MemoryKeyValueStore()
    KeyValueFrequencyStore(kv).record("nudge", 5_000)
    assertEquals(1, KeyValueFrequencyStore(kv).impressions("nudge", 0))
    assertEquals(0, KeyValueFrequencyStore(kv).impressions("nudge", 6_000))
  }
}
