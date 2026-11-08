package ee.schimke.livery.client

import ee.schimke.livery.protocol.Arm
import ee.schimke.livery.protocol.Experiment
import ee.schimke.livery.protocol.InstallPolicy
import ee.schimke.livery.protocol.Manifest
import ee.schimke.livery.protocol.TemplateEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A slot that asks most specific first (PROTOCOL.md §5.4): the first query with a template decides. */
class FirstMatchTest {
  // The retro design is tagged for one track and keeps its own variant, so the plain query's
  // default still draws everywhere else.
  private val m: Manifest = Fx.manifest().let { m ->
    m.copy(designs = m.designs.map { if (it.id == "tile-retro") it.copy(install = InstallPolicy.Always, tags = mapOf("track" to "android")) else it })
  }

  private val repo = TemplateRepository(
    StaticSource("https://cdn.example/c"), { _, _ -> FetchResult.Failed(503, "down") }, FakeCrypto, TrustPolicy.AllowUnsigned,
    MemoryBlobStore(), MemoryKeyValueStore(), { 1_000 }, listOf(Fx.inApp),
    TemplateRepository.Bundled(m) { sha -> listOf("default-v7", "retro-v7").map(Fx::blob).firstOrNull { FakeCrypto.sha(it) == sha } },
  )
  private val events = mutableListOf<TemplateEvent>()
  private val planner = SlotPlanner(repo, { events += it }, { 1_000 })
  private val plain = TemplateQuery("live/status-tile@1", Fx.inApp.copy(maxRcApiLevel = 7))
  private fun track(t: String) = plain.copy(tags = mapOf("track" to t))
  private val data = JsonObject(mapOf("name" to JsonPrimitive("Hall")))

  private suspend fun design(vararg queries: TemplateQuery) =
    assertIs<PlanResult.Ready>(planner.forAppData(queries.toList(), data)).plan.template.entry.designId

  @Test fun theMostSpecificQueryWithATemplateDecides() = runTest {
    assertEquals("tile-retro", design(track("android"), plain))
    assertEquals(0, repo.resolveFirst(listOf(track("android"), plain))?.index)
  }

  @Test fun oneWithoutFallsBackToTheNext() = runTest {
    assertEquals("tile-default", design(track("ios"), plain))
    assertEquals(1, repo.resolveFirst(listOf(track("ios"), plain))?.index)
    // The plain query alone keeps the default: the tagged design has its own variant.
    assertEquals("tile-default", design(plain))
  }

  @Test fun noQueryWithATemplateFallsBackOnce() = runTest {
    assertIs<PlanResult.Fallback>(planner.forAppData(listOf(track("ios"), track("wear")), data))
    assertEquals(1, events.count { it.type == TemplateEvent.Type.Fallback })
  }

  @Test fun anArmsDesignThatOnlyAnArmMayPickStillDraws() = runTest {
    // A season: shipped, never picked automatically, and the only design here.
    val season = Experiment("season", listOf(Arm("winter", designs = mapOf("live/status-tile@1" to "tile-retro"))))
    val m = Fx.manifest().let { m ->
      m.copy(
        experiments = listOf(season),
        designs = m.designs.mapNotNull {
          when (it.id) {
            "tile-retro" -> it.copy(install = InstallPolicy.Always, automatic = false)
            else -> null
          }
        },
      )
    }
    val repo = TemplateRepository(
      StaticSource("https://cdn.example/c"), { _, _ -> FetchResult.Failed(503, "down") }, FakeCrypto, TrustPolicy.AllowUnsigned,
      MemoryBlobStore(), MemoryKeyValueStore(), { 1_000 }, listOf(Fx.inApp),
      TemplateRepository.Bundled(m) { sha -> listOf("retro-v7").map(Fx::blob).firstOrNull { FakeCrypto.sha(it) == sha } },
    )
    val ctx = DecisionContext(1_000, "android", "en-GB", 10, "u")
    val experiments = Experiments({ m }, { ctx }, { _, _ -> "winter" }, EventSink {})
    val planner = SlotPlanner(repo, {}, { 1_000 }, experiments)
    val single = assertIs<PlanResult.Ready>(planner.forAppData(plain, data)).plan
    val first = assertIs<PlanResult.Ready>(planner.forAppData(listOf(track("android"), plain), data)).plan
    assertEquals("tile-retro", single.template.entry.designId)
    assertEquals(single.template.entry.designId, first.template.entry.designId)
    assertEquals(mapOf("season" to "winter"), first.experiments)
  }

  @Test fun queriesMustShareOneContract() = runTest {
    assertFailsWith<IllegalArgumentException> { planner.forAppData(listOf(plain, plain.copy(contract = "live/other@1")), data) }
    assertFailsWith<IllegalArgumentException> { planner.forAppData(emptyList(), data) }
  }
}
