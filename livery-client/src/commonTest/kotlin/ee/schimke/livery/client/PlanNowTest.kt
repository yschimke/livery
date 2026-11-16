package ee.schimke.livery.client

import ee.schimke.livery.protocol.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `SlotPlanner.forAppDataNow`: a plan from memory, for the first answer a slot draws when its data changes. */
class PlanNowTest {
  private val m: Manifest = Fx.manifest()
  private val repo = TemplateRepository(
    StaticSource("https://cdn.example/c"), { _, _ -> FetchResult.Failed(503, "down") }, FakeCrypto, TrustPolicy.AllowUnsigned,
    MemoryBlobStore(), MemoryKeyValueStore(), { 1_000 }, listOf(Fx.inApp),
    TemplateRepository.Bundled(m) { sha -> listOf("default-v7", "retro-v7").map(Fx::blob).firstOrNull { FakeCrypto.sha(it) == sha } },
  )
  private val planner = SlotPlanner(repo, {}, { 1_000 })
  private val query = TemplateQuery("live/status-tile@1", Fx.inApp.copy(maxRcApiLevel = 7))
  private fun data(name: String) = JsonObject(mapOf("name" to JsonPrimitive(name)))

  @Test fun nothingBeforeTheFirstPlanHasResolvedTheTemplate() {
    assertNull(planner.forAppDataNow(query, data("Hall")))
  }

  @Test fun afterAPlanNewDataIsPlannedFromMemoryToTheSameAnswer() = runTest {
    val usual = assertIs<PlanResult.Ready>(planner.forAppData(query, data("Hall")))
    val now = assertNotNull(planner.forAppDataNow(query, data("Hall")))
    assertEquals(usual.plan, now.plan)
    val other = assertNotNull(planner.forAppDataNow(query, data("Kitchen")))
    assertEquals(usual.plan.template, other.plan.template)
    assertEquals(false, usual.plan.bindings == other.plan.bindings)
  }

  @Test fun dataTheContractRejectsIsLeftToTheUsualPlan() = runTest {
    planner.forAppData(query, data("Hall"))
    assertNull(planner.forAppDataNow(query, JsonObject(mapOf("name" to JsonPrimitive(1)))))
  }

  @Test fun aChangedCatalogMeansPlanningTheUsualWayAgain() = runTest {
    planner.forAppData(query, data("Hall"))
    assertNotNull(planner.forAppDataNow(query, data("Hall")))
    // An uninstall changes what the catalog chooses: the answer is only ever for the revision it was resolved at.
    repo.uninstall("tile-retro")
    assertNull(planner.forAppDataNow(query, data("Hall")))
    // The usual plan resolves it again, at the new revision.
    planner.forAppData(query, data("Hall"))
    assertNotNull(planner.forAppDataNow(query, data("Hall")))
  }
}
