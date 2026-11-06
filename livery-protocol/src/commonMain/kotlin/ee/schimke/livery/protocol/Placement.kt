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
  /** Only for users assigned this arm of a manifest [Experiment] (PROTOCOL.md §8.1). */
  val arm: ArmRef? = null,
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

/**
 * An A/B test (PROTOCOL.md §8.1): users who match [conditions] during the schedule are enrolled
 * at [allocationBasisPoints] (10 000 = all of them), and each enrolled user gets one [Arm], sticky,
 * in proportion to the arms' weights. An arm can pick the design a contract's slots use, and
 * placement rules can be limited to an arm. Assignment is local by default; an app can hand it to
 * a remote service (Firebase Remote Config, its own) through `ExperimentAssigner`.
 */
@Serializable
data class Experiment(
  val id: String,
  val arms: List<Arm>,
  val startEpochMs: Long? = null,
  val endEpochMs: Long? = null,
  val conditions: Conditions = Conditions(),
  val allocationBasisPoints: Int = 10_000,
)

/** One arm: its share among the [Experiment]'s arms, and the design it picks per contract (`id@major` → design id). */
@Serializable
data class Arm(val id: String, val weight: Int = 1, val designs: Map<String, String> = emptyMap())

/** An arm of a manifest experiment, by id. */
@Serializable
data class ArmRef(val experiment: String, val arm: String)

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
