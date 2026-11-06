package ee.schimke.livery.protocol

import kotlinx.serialization.SerialName
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
  /** How a screen placement shows it (PROTOCOL.md §8.2); [Presentation.Inline] leaves it to the placement. */
  val presentation: Presentation = Presentation.Inline,
  /** The screens its `present` actions open, by action name, so opening one never waits on the network. */
  val screens: Map<String, Screen> = emptyMap(),
)

/**
 * How a placement's template is shown (PROTOCOL.md §8.2): in a slot ([Inline]), or as a secondary
 * screen. A screen's template is built for it: its target's container is [container].
 */
@Serializable
enum class Presentation {
  @SerialName("inline") Inline,
  @SerialName("dialog") Dialog,
  @SerialName("sheet") Sheet,
  @SerialName("fullscreen") Fullscreen;

  /** The `Target.container` of a template for this presentation (`screen:dialog`, …); null inline. */
  val container: String? get() = when (this) {
    Inline -> null
    Dialog -> "screen:dialog"
    Sheet -> "screen:sheet"
    Fullscreen -> "screen:fullscreen"
  }

  companion object {
    /** The presentation a `screen:*` container is for, or null. */
    fun ofContainer(container: String?): Presentation? = entries.firstOrNull { it != Inline && it.container == container }
  }
}

/** A secondary screen a decision ships with: what a `present` action opens (PROTOCOL.md §8.2). */
@Serializable
data class Screen(
  val template: TemplateRef,
  val data: JsonObject = JsonObject(emptyMap()),
  /** [Presentation.Inline] leaves it to the placement's default. */
  val presentation: Presentation = Presentation.Inline,
)

/**
 * Named values a host writes into a screen's template (PROTOCOL.md §8.2): the safe-area insets in
 * dp, so a full-screen template lays out around cut-outs and system bars. Never data: a contract
 * needn't declare them.
 */
object ScreenValues {
  const val INSET_TOP: String = "livery.inset.top"
  const val INSET_BOTTOM: String = "livery.inset.bottom"
  const val INSET_START: String = "livery.inset.start"
  const val INSET_END: String = "livery.inset.end"
  val NAMES: Set<String> = setOf(INSET_TOP, INSET_BOTTOM, INSET_START, INSET_END)
}

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
  /** For a screen placement, how its decision shows (PROTOCOL.md §8.2). */
  val presentation: Presentation = Presentation.Inline,
  /** The screens its template's `present` actions open, by action name. */
  val screens: Map<String, Screen> = emptyMap(),
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
