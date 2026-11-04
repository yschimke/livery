package ee.schimke.livery.client

import ee.schimke.livery.protocol.Decision
import ee.schimke.livery.protocol.FeedPage
import ee.schimke.livery.protocol.Manifest
import ee.schimke.livery.protocol.PlacementRule

/** What a decider may know about the user: coarse, app-reported, never sent to the catalog host. */
data class DecisionContext(
  val nowEpochMs: Long,
  val platform: String,
  val locale: String,
  val appVersion: Long,
  /** Stable, app-scoped, resettable id used only for sticky rollout buckets. */
  val bucketingId: String,
  val segments: Set<String> = emptySet(),
)

/** Picks what a placement shows. Local rules, a remote ad/promo server, or both in order. */
fun interface Decider {
  suspend fun decide(placement: String, context: DecisionContext): Decision?
}

/** Impression counts per rule, for frequency caps. Persist it; an in-memory one resets per run. */
interface FrequencyStore {
  suspend fun impressions(ruleId: String, sinceEpochMs: Long): Int
  suspend fun record(ruleId: String, atEpochMs: Long)
}

class MemoryFrequencyStore : FrequencyStore {
  private val log = mutableMapOf<String, MutableList<Long>>()
  override suspend fun impressions(ruleId: String, sinceEpochMs: Long) = log[ruleId]?.count { it >= sinceEpochMs } ?: 0
  override suspend fun record(ruleId: String, atEpochMs: Long) { log.getOrPut(ruleId) { mutableListOf() } += atEpochMs }
}

/**
 * Evaluates the manifest's [PlacementRule]s on the device: schedule, conditions, sticky rollout,
 * frequency cap, then the highest priority wins. Works offline and keeps targeting data local.
 */
class LocalRulesDecider(
  private val manifest: suspend () -> Manifest?,
  private val frequency: FrequencyStore,
) : Decider {
  override suspend fun decide(placement: String, context: DecisionContext): Decision? {
    val rules = manifest()?.placements.orEmpty().filter { it.placement == placement }
    val eligible = rules.filter { eligible(it, context) }
    val rule = eligible.maxWithOrNull(compareBy<PlacementRule> { it.priority }.thenByDescending { it.id }) ?: return null
    return Decision(
      placement = placement,
      decisionId = "local:${rule.id}",
      template = rule.template,
      data = rule.data,
      tracking = rule.tracking,
      experiments = rule.rollout?.let { mapOf(it.experiment to it.arm) } ?: emptyMap(),
    )
  }

  private suspend fun eligible(r: PlacementRule, c: DecisionContext): Boolean {
    r.startEpochMs?.let { if (c.nowEpochMs < it) return false }
    r.endEpochMs?.let { if (c.nowEpochMs >= it) return false }
    val k = r.conditions
    if (k.platforms.isNotEmpty() && c.platform !in k.platforms) return false
    if (k.locales.isNotEmpty() && k.locales.none { c.locale == it || c.locale.startsWith("$it-") }) return false
    k.minAppVersion?.let { if (c.appVersion < it) return false }
    k.maxAppVersion?.let { if (c.appVersion > it) return false }
    if (!c.segments.containsAll(k.segments)) return false
    if (k.excludeSegments.any { it in c.segments }) return false
    r.rollout?.let { if (bucket(it.experiment, c.bucketingId) !in it.fromBasisPoints until it.toBasisPoints) return false }
    r.frequency?.let { if (frequency.impressions(r.id, c.nowEpochMs - it.perSeconds * 1000) >= it.maxImpressions) return false }
    return true
  }

  /** Recorded by the slot when the template's first frame draws. */
  suspend fun recordImpression(decision: Decision, atEpochMs: Long) {
    if (decision.decisionId.startsWith("local:")) frequency.record(decision.decisionId.removePrefix("local:"), atEpochMs)
  }

  companion object {
    /** 0 until 10_000, stable for an experiment and id on every platform (FNV-1a 32). */
    fun bucket(experiment: String, id: String): Int {
      var h = 0x811C9DC5.toInt()
      for (b in "$experiment:$id".encodeToByteArray()) { h = h xor (b.toInt() and 0xFF); h *= 0x01000193 }
      return ((h.toLong() and 0xFFFFFFFFL) % 10_000).toInt()
    }
  }
}

/** Tries each decider in order: e.g. a remote ad server first, local house promos when it has none. */
class FirstDecider(private val deciders: List<Decider>) : Decider {
  override suspend fun decide(placement: String, context: DecisionContext): Decision? =
    deciders.firstNotNullOfOrNull { runCatching { it.decide(placement, context) }.getOrNull() }
}

/** Loads feed pages from the app's own feed API; the toolkit only defines the payload shape. */
fun interface FeedClient {
  suspend fun page(feed: String, cursor: String?): FeedPage
}
