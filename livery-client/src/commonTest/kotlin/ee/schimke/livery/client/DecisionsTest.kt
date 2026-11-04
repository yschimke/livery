package ee.schimke.livery.client

import ee.schimke.livery.protocol.Conditions
import ee.schimke.livery.protocol.FrequencyCap
import ee.schimke.livery.protocol.PlacementRule
import ee.schimke.livery.protocol.Rollout
import ee.schimke.livery.protocol.TemplateRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class DecisionsTest {
  private val ctx = DecisionContext(nowEpochMs = 1_000, platform = "android", locale = "en-GB", appVersion = 10, bucketingId = "u1")
  private fun rule(id: String, priority: Int = 0, block: PlacementRule.() -> PlacementRule = { this }) =
    PlacementRule(id, "home.top", TemplateRef(designId = "banner"), priority = priority).block()

  @Test fun highestEligiblePriorityWins() = runTest {
    val m = Fx.manifest().copy(placements = listOf(
      rule("a", 1), rule("b", 5) { copy(conditions = Conditions(segments = setOf("subscriber"))) }, rule("c", 3),
    ))
    assertEquals("local:c", LocalRulesDecider({ m }, MemoryFrequencyStore()).decide("home.top", ctx)?.decisionId)
  }

  @Test fun scheduleLocaleAndRollout() = runTest {
    val m = Fx.manifest().copy(placements = listOf(
      rule("future") { copy(startEpochMs = 2_000) },
      rule("fr") { copy(conditions = Conditions(locales = setOf("fr"))) },
      rule("none") { copy(rollout = Rollout("exp", "a", 0, 0)) },
    ))
    assertNull(LocalRulesDecider({ m }, MemoryFrequencyStore()).decide("home.top", ctx))
    assertEquals(true, LocalRulesDecider.bucket("exp", "u1") in 0 until 10_000)
    assertEquals(LocalRulesDecider.bucket("exp", "u1"), LocalRulesDecider.bucket("exp", "u1"))
  }

  @Test fun frequencyCap() = runTest {
    val m = Fx.manifest().copy(placements = listOf(rule("capped") { copy(frequency = FrequencyCap(2, 3600)) }))
    val d = LocalRulesDecider({ m }, MemoryFrequencyStore())
    repeat(2) { d.recordImpression(d.decide("home.top", ctx)!!, ctx.nowEpochMs) }
    assertNull(d.decide("home.top", ctx))
  }
}
