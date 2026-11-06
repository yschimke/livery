package ee.schimke.livery.firebase

import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import ee.schimke.livery.client.AnalyticsEvents
import ee.schimke.livery.client.AnalyticsSink
import ee.schimke.livery.client.DecisionContext
import ee.schimke.livery.client.ExperimentAssigner
import ee.schimke.livery.client.FrequencyBackend
import ee.schimke.livery.client.ImpressionRecord
import ee.schimke.livery.protocol.Experiment
import kotlinx.coroutines.tasks.await

/**
 * Livery's events in Firebase Analytics, in [AnalyticsEvents]' shape (`livery_impression`,
 * `livery_exposure`, … with `exp_<experiment>` parameters), which fits Firebase's limits:
 *
 * ```
 * val events = AnalyticsEventSink(FirebaseAnalyticsSink(Firebase.analytics))
 * SlotPlanner(repository, events, clock, experiments)
 * ```
 *
 * [userProperties] also sets each exposure's arm as a user property (`exp_<experiment>`), so
 * Firebase can split every other event, not only Livery's, by arm. Firebase allows 25.
 */
class FirebaseAnalyticsSink(
  private val analytics: FirebaseAnalytics,
  private val userProperties: Boolean = true,
) : AnalyticsSink {
  override fun log(name: String, params: Map<String, Any>) {
    analytics.logEvent(name, Bundle().apply {
      for ((k, v) in params) when (v) {
        is Long -> putLong(k, v)
        is Int -> putLong(k, v.toLong())
        is Double -> putDouble(k, v)
        is Float -> putDouble(k, v.toDouble())
        is Boolean -> putLong(k, if (v) 1 else 0)
        else -> putString(k, v.toString())
      }
    })
    if (userProperties && name == AnalyticsEvents.PREFIX + "exposure") {
      for ((k, v) in params) if (k.startsWith("exp_")) analytics.setUserProperty(k.take(24), v.toString().take(36))
    }
  }
}

/**
 * Experiment arms from Firebase Remote Config, which is also how Firebase A/B Testing hands them
 * out: the parameter `livery_exp_<experiment>` (letters, digits and `_`) holds the arm id. It reads
 * only the activated config and never fetches: call `fetchAndActivate()` at app start as usual.
 * Put it before `LocalBucketing` in a `FirstAssigner`, so experiments Remote Config doesn't run
 * still bucket locally:
 *
 * ```
 * Experiments({ repo.manifest() }, { context }, FirstAssigner(RemoteConfigAssigner(Firebase.remoteConfig), LocalBucketing))
 * ```
 */
class RemoteConfigAssigner(
  private val config: FirebaseRemoteConfig,
  private val prefix: String = "livery_exp_",
) : ExperimentAssigner {
  override suspend fun assign(experiment: Experiment, context: DecisionContext): String? =
    config.getString(parameter(experiment.id)).takeIf { it.isNotBlank() }

  /** The Remote Config parameter for [experimentId]. */
  fun parameter(experimentId: String): String = AnalyticsEvents.paramName(prefix + experimentId).let { if (it.startsWith(prefix)) it else prefix + it }
}

/**
 * Frequency caps shared by a user's devices, in Firestore: one document per impression under
 * `<collection>/<userId>/impressions`, which a `SyncedFrequencyStore` pushes and pulls in its
 * `sync()`. [userId] is the signed-in user's id; Firestore security rules should let each user read
 * and write only their own (`request.auth.uid == userId`).
 *
 * ```
 * val caps = SyncedFrequencyStore(kv, FirestoreFrequencyBackend(Firebase.firestore, user.uid), deviceId, clock)
 * LocalRulesDecider({ repo.manifest() }, caps)   // then caps.sync() at start and after impressions
 * ```
 */
class FirestoreFrequencyBackend(
  private val firestore: FirebaseFirestore,
  private val userId: String,
  private val collection: String = "livery",
) : FrequencyBackend {
  private fun impressions() = firestore.collection(collection).document(userId).collection("impressions")

  override suspend fun push(records: List<ImpressionRecord>) {
    if (records.isEmpty()) return
    for (chunk in records.chunked(MAX_BATCH)) {
      val batch = firestore.batch()
      for (r in chunk) batch.set(impressions().document(id(r)), mapOf("ruleId" to r.ruleId, "atEpochMs" to r.atEpochMs, "device" to r.device, "seq" to r.seq), SetOptions.merge())
      batch.commit().await()
    }
  }

  override suspend fun pull(sinceEpochMs: Long): List<ImpressionRecord> =
    impressions().whereGreaterThanOrEqualTo("atEpochMs", sinceEpochMs).get().await().documents.mapNotNull { d ->
      val rule = d.getString("ruleId") ?: return@mapNotNull null
      val at = d.getLong("atEpochMs") ?: return@mapNotNull null
      ImpressionRecord(rule, at, d.getString("device").orEmpty(), (d.getLong("seq") ?: 0).toInt())
    }

  /** One document per record, so pushing again after a failure writes it once. */
  private fun id(r: ImpressionRecord): String = impressionDocumentId(r)

  private companion object {
    /** Firestore's limit on writes in one batch. */
    const val MAX_BATCH = 500
  }
}

/**
 * An impression's Firestore document id: SHA-256 (hex) of its whole identity, length-prefixed so
 * no two records share one (`promo/a` and `promo_a`, a device id with a `-`), and always a legal id.
 */
internal fun impressionDocumentId(r: ImpressionRecord): String {
  val key = "${r.device.length}:${r.device}|${r.atEpochMs}|${r.seq}|${r.ruleId.length}:${r.ruleId}"
  return java.security.MessageDigest.getInstance("SHA-256").digest(key.encodeToByteArray()).joinToString("") { "%02x".format(it) }
}
