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
 * experiment arm, frequency cap, then the highest priority wins. Works offline and keeps targeting
 * data local. [experiments] assigns arms for rules limited to one ([PlacementRule.arm]); the
 * decision names every experiment that chose it, for analytics.
 */
class LocalRulesDecider(
  private val manifest: suspend () -> Manifest?,
  private val frequency: FrequencyStore,
  private val experiments: ExperimentAssigner = LocalBucketing,
) : Decider {
  override suspend fun decide(placement: String, context: DecisionContext): Decision? {
    val m = manifest()
    val rules = m?.placements.orEmpty().filter { it.placement == placement }
    val eligible = rules.filter { eligible(it, context, m) }
    val rule = eligible.maxWithOrNull(compareBy<PlacementRule> { it.priority }.thenByDescending { it.id }) ?: return null
    return Decision(
      placement = placement,
      decisionId = "local:${rule.id}",
      template = rule.template,
      data = rule.data,
      tracking = rule.tracking,
      experiments = listOfNotNull(rule.rollout?.let { it.experiment to it.arm }, rule.arm?.let { it.experiment to it.arm }).toMap(),
    )
  }

  private suspend fun eligible(r: PlacementRule, c: DecisionContext, m: Manifest?): Boolean {
    r.startEpochMs?.let { if (c.nowEpochMs < it) return false }
    r.endEpochMs?.let { if (c.nowEpochMs >= it) return false }
    if (!r.conditions.matches(c)) return false
    r.rollout?.let { if (bucket(it.experiment, c.bucketingId) !in it.fromBasisPoints until it.toBasisPoints) return false }
    r.arm?.let { ref ->
      val e = m?.experiments?.firstOrNull { it.id == ref.experiment } ?: return false
      if (!LocalBucketing.running(e, c)) return false
      if (runCatching { experiments.assign(e, c) }.getOrNull() != ref.arm) return false
    }
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

/**
 * One impression a frequency cap counts, with the device that drew it and a [seq] that tells two
 * impressions in the same millisecond apart, so synced logs merge each record exactly once.
 */
@kotlinx.serialization.Serializable
data class ImpressionRecord(val ruleId: String, val atEpochMs: Long, val device: String, val seq: Int = 0)

/**
 * A [FrequencyStore] that survives restarts, in a [KeyValueStore] (files, SharedPreferences, …).
 * Records older than [retainMs] are dropped as new ones arrive: caps look back at most that far.
 */
open class KeyValueFrequencyStore(
  private val kv: KeyValueStore,
  private val device: String = "local",
  private val retainMs: Long = 90L * 24 * 3_600_000,
) : FrequencyStore {
  protected val lock = kotlinx.coroutines.sync.Mutex()
  private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
  private val serializer = kotlinx.serialization.builtins.ListSerializer(ImpressionRecord.serializer())

  override suspend fun impressions(ruleId: String, sinceEpochMs: Long): Int =
    lock.withLockSafe { read(ruleId).count { it.atEpochMs >= sinceEpochMs } }

  override suspend fun record(ruleId: String, atEpochMs: Long) {
    val record = lock.withLockSafe {
      val existing = read(ruleId)
      val r = ImpressionRecord(ruleId, atEpochMs, device, existing.count { it.device == device && it.atEpochMs == atEpochMs })
      write(ruleId, existing + r, atEpochMs)
      r
    }
    recorded(record)
  }

  /** Adds records from elsewhere (another device's), once each. */
  suspend fun merge(records: List<ImpressionRecord>, nowEpochMs: Long) {
    lock.withLockSafe {
      for ((rule, incoming) in records.groupBy { it.ruleId }) write(rule, (read(rule) + incoming).distinct(), nowEpochMs)
    }
  }

  /** Called after a local impression is stored; [SyncedFrequencyStore] queues it for the backend. */
  protected open suspend fun recorded(record: ImpressionRecord) {}

  protected suspend fun read(ruleId: String): List<ImpressionRecord> =
    kv.get(key(ruleId))?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

  private suspend fun write(ruleId: String, records: List<ImpressionRecord>, nowEpochMs: Long) =
    kv.put(key(ruleId), json.encodeToString(serializer, records.filter { it.atEpochMs >= nowEpochMs - retainMs }.sortedBy { it.atEpochMs }))

  private fun key(ruleId: String) = "livery.frequency.$ruleId"
}

/**
 * Where impressions are shared between a user's devices, so a cap holds across all of them (show a
 * promotion twice a week, not twice per phone): Firestore (`livery-firebase`) or the app's own
 * service. [push] and [pull] run only in [SyncedFrequencyStore.sync], never while a slot decides.
 */
interface FrequencyBackend {
  suspend fun push(records: List<ImpressionRecord>)
  /** Records stored since [sinceEpochMs] (by impression time), from every device. */
  suspend fun pull(sinceEpochMs: Long): List<ImpressionRecord>
}

/**
 * Frequency caps across devices. Decisions read only the local store, so rendering never waits on
 * the network; impressions are queued and [sync] (on app start, on a schedule, after a placement
 * draws) pushes them and pulls the other devices'. A cap is at most one sync out of date.
 */
class SyncedFrequencyStore(
  private val kv: KeyValueStore,
  private val backend: FrequencyBackend,
  device: String,
  private val clock: Clock,
  private val lookbackMs: Long = 90L * 24 * 3_600_000,
) : KeyValueFrequencyStore(kv, device, lookbackMs) {
  private val pendingSerializer = kotlinx.serialization.builtins.ListSerializer(ImpressionRecord.serializer())
  private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
  private val syncLock = kotlinx.coroutines.sync.Mutex()

  override suspend fun recorded(record: ImpressionRecord) {
    lock.withLockSafe { kv.put(PENDING, json.encodeToString(pendingSerializer, pending() + record)) }
  }

  /** Pushes what this device recorded and pulls everyone's; false when the backend failed (retried next time). */
  suspend fun sync(): Boolean = syncLock.withLockSafe {
    runCatching {
      val queued = lock.withLockSafe { pending() }
      if (queued.isNotEmpty()) backend.push(queued)
      lock.withLockSafe { kv.put(PENDING, json.encodeToString(pendingSerializer, pending() - queued.toSet())) }
      val now = clock.nowEpochMs()
      val since = kv.get(PULLED)?.toLongOrNull()?.let { maxOf(it, now - lookbackMs) } ?: (now - lookbackMs)
      merge(backend.pull(since), now)
      kv.put(PULLED, now.toString())
    }.isSuccess
  }

  private suspend fun pending(): List<ImpressionRecord> =
    kv.get(PENDING)?.let { runCatching { json.decodeFromString(pendingSerializer, it) }.getOrNull() }.orEmpty()

  private companion object {
    const val PENDING = "livery.frequency~pending"
    const val PULLED = "livery.frequency~pulled"
  }
}

private suspend inline fun <T> kotlinx.coroutines.sync.Mutex.withLockSafe(block: () -> T): T {
  lock()
  try { return block() } finally { unlock() }
}
