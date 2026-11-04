package ee.schimke.livery.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Names a template without fixing its bytes: by design (the client picks the entry for its target)
 * or by an exact blob. Feeds and decisions use this so one payload serves every device.
 */
@Serializable
data class TemplateRef(
  val designId: String? = null,
  val sha256: String? = null,
  val contract: String? = null,
) {
  init {
    require(designId != null || sha256 != null) { "a TemplateRef needs a designId or a sha256" }
  }
}

/** Measurement hooks a decision or feed item carries. */
@Serializable
data class Tracking(
  val impression: List<String> = emptyList(),
  val viewable: List<String> = emptyList(),
  val click: List<String> = emptyList(),
  val dismiss: List<String> = emptyList(),
)

/**
 * What a decision service answers for one placement: which template, with what data. This is the
 * shape for ads, promotions and anything targeted server-side.
 */
@Serializable
data class Decision(
  val placement: String,
  val decisionId: String,
  val template: TemplateRef,
  val data: JsonObject = JsonObject(emptyMap()),
  val ttlSeconds: Long = 300,
  val tracking: Tracking = Tracking(),
  /** Experiment arms this decision belongs to, for analytics. */
  val experiments: Map<String, String> = emptyMap(),
)

/**
 * A client-side rule for a placement, shipped in the manifest, for growth features that must work
 * offline or without a decision service: a promotion window, a rollout, a capped nudge.
 */
@Serializable
data class PlacementRule(
  val id: String,
  val placement: String,
  val template: TemplateRef,
  val data: JsonObject = JsonObject(emptyMap()),
  val priority: Int = 0,
  val startEpochMs: Long? = null,
  val endEpochMs: Long? = null,
  val conditions: Conditions = Conditions(),
  val rollout: Rollout? = null,
  val frequency: FrequencyCap? = null,
  val tracking: Tracking = Tracking(),
)

@Serializable
data class Conditions(
  val platforms: Set<String> = emptySet(),
  val locales: Set<String> = emptySet(),
  val minAppVersion: Long? = null,
  val maxAppVersion: Long? = null,
  /** Segments the app reports for the user (`subscriber`, `new-user`); all must match. */
  val segments: Set<String> = emptySet(),
  val excludeSegments: Set<String> = emptySet(),
)

/** A sticky percentage rollout: hash(experiment, stable user id) mod 10_000 < basisPoints. */
@Serializable
data class Rollout(val experiment: String, val arm: String, val fromBasisPoints: Int = 0, val toBasisPoints: Int)

@Serializable
data class FrequencyCap(val maxImpressions: Int, val perSeconds: Long)

/** A page of a feed: items each naming a template and carrying their own data. */
@Serializable
data class FeedPage(
  val feed: String,
  val items: List<FeedItem>,
  val next: String? = null,
  val ttlSeconds: Long = 60,
)

@Serializable
data class FeedItem(
  val id: String,
  val template: TemplateRef,
  val data: JsonObject = JsonObject(emptyMap()),
  val tracking: Tracking = Tracking(),
)
