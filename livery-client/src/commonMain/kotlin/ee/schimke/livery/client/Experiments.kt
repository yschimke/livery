package ee.schimke.livery.client

import ee.schimke.livery.protocol.Arm
import ee.schimke.livery.protocol.Conditions
import ee.schimke.livery.protocol.Experiment
import ee.schimke.livery.protocol.Manifest
import ee.schimke.livery.protocol.TemplateEvent
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Who decides which arm of an [Experiment] a user is in. The default, [LocalBucketing], needs no
 * service; an app hands assignment to Firebase Remote Config (`livery-firebase`) or its own
 * experimentation service by implementing this, usually ahead of [LocalBucketing] in a
 * [FirstAssigner].
 *
 * Return an arm id of [experiment], or null when this assigner has no answer (not enrolled, not
 * fetched yet). Never wait on the network: answer from what is already on the device.
 */
fun interface ExperimentAssigner {
  suspend fun assign(experiment: Experiment, context: DecisionContext): String?
}

/**
 * Sticky, offline assignment (PROTOCOL.md §8.1): within the schedule, for users matching the
 * conditions, a user is enrolled when `bucket(experiment.id, bucketingId) < allocationBasisPoints`,
 * in the arm whose cumulative weight covers `bucket(experiment.id + "#arm", bucketingId) ×
 * totalWeight / 10000`. Two independent hashes: raising the allocation only adds users, and an
 * enrolled user keeps their arm.
 */
object LocalBucketing : ExperimentAssigner {
  override suspend fun assign(experiment: Experiment, context: DecisionContext): String? = assignNow(experiment, context)

  fun assignNow(experiment: Experiment, context: DecisionContext): String? {
    if (!running(experiment, context)) return null
    val arms = experiment.arms.filter { it.weight > 0 }
    val total = arms.sumOf { it.weight.toLong() }
    val allocation = experiment.allocationBasisPoints.coerceIn(0, 10_000)
    if (total == 0L || allocation == 0) return null
    if (LocalRulesDecider.bucket(experiment.id, context.bucketingId) >= allocation) return null
    val position = LocalRulesDecider.bucket(experiment.id + ARM_SALT, context.bucketingId).toLong() * total / 10_000
    var upTo = 0L
    for (arm in arms) {
      upTo += arm.weight
      if (position < upTo) return arm.id
    }
    return arms.last().id
  }

  /** Appended to the experiment id for the arm's bucket, so it is independent of enrolment's. */
  const val ARM_SALT: String = "#arm"

  /** Whether [experiment] runs for this user now: its schedule and conditions. */
  fun running(experiment: Experiment, context: DecisionContext): Boolean {
    experiment.startEpochMs?.let { if (context.nowEpochMs < it) return false }
    experiment.endEpochMs?.let { if (context.nowEpochMs >= it) return false }
    return experiment.conditions.matches(context)
  }
}

/** Asks each assigner in turn; the first arm that exists in the experiment wins. */
class FirstAssigner(private val assigners: List<ExperimentAssigner>) : ExperimentAssigner {
  constructor(vararg assigners: ExperimentAssigner) : this(assigners.toList())

  override suspend fun assign(experiment: Experiment, context: DecisionContext): String? =
    assigners.firstNotNullOfOrNull { a -> runCatching { a.assign(experiment, context) }.getOrNull()?.takeIf { id -> experiment.arms.any { it.id == id } } }
}

/**
 * The manifest's experiments for one app: which arm the user is in, which design an arm picks for
 * a contract, and one [TemplateEvent.Type.Exposure] per experiment and arm the first time it shapes
 * what draws (exposure, not assignment, is what an A/B analysis counts).
 *
 * Exposure follows drawing, not planning: give slots [sink] as their event sink, and the first
 * `Impression` carrying an arm (a design arm's plan, a decision from an arm's rule) reports it.
 * A host that never reports impressions (a launcher or Wear widget) calls [exposed] when it knows
 * the user saw the plan.
 */
@OptIn(ExperimentalAtomicApi::class)
class Experiments(
  private val manifest: suspend () -> Manifest?,
  private val context: suspend () -> DecisionContext,
  private val assigner: ExperimentAssigner = LocalBucketing,
  private val events: EventSink = EventSink { },
) {
  private val exposed = AtomicReference(emptySet<Pair<String, String>>())

  /** Passes every event to [events], adding one Exposure the first time an arm's template draws. */
  val sink: EventSink = EventSink { e ->
    events.emit(e)
    if (e.type == TemplateEvent.Type.Impression) {
      for ((experiment, arm) in e.experiments) exposed(experiment, arm, e.atEpochMs, e.placement, e.designId)
    }
  }

  /** The user's arm of [experimentId], or null when it isn't running for them. */
  suspend fun arm(experimentId: String): String? {
    val e = manifest()?.experiments?.firstOrNull { it.id == experimentId } ?: return null
    return armOf(e, context())?.id
  }

  /**
   * The user's design arm for [contract] (`id@major`), or null to select as usual: see [designArm].
   * [DesignArm.experiment] and [DesignArm.arm] go on the plan so every event carries them.
   */
  suspend fun designFor(contract: String): DesignArm? {
    val ctx = context()
    return designArm(manifest()?.experiments.orEmpty(), contract, ctx) { e -> armOf(e, ctx)?.id }
  }

  /** Reports the exposure once per experiment and arm for this [Experiments]. */
  fun exposed(experiment: String, arm: String, atEpochMs: Long, placement: String? = null, designId: String? = null) {
    val key = experiment to arm
    while (true) {
      val seen = exposed.load()
      if (key in seen) return
      if (exposed.compareAndSet(seen, seen + key)) break
    }
    events.emit(TemplateEvent(TemplateEvent.Type.Exposure, atEpochMs, placement, designId = designId, experiments = mapOf(experiment to arm)))
  }

  private suspend fun armOf(e: Experiment, ctx: DecisionContext): Arm? {
    if (!LocalBucketing.running(e, ctx)) return null
    val id = runCatching { assigner.assign(e, ctx) }.getOrNull() ?: return null
    return e.arms.firstOrNull { it.id == id }
  }
}

/**
 * A user's arm of an experiment over a contract's design. [designId] is the design the arm picks,
 * or null for an arm that picks none (a control): that user draws the usual choice, and still
 * carries the arm, so an analysis has its control group.
 */
data class DesignArm(val experiment: String, val arm: String, val designId: String?)

/**
 * PROTOCOL.md §8.1: the design arm for a slot of [contract]. Only experiments with an arm that
 * names a design for [contract] apply; the first of those, in manifest order, that is running and
 * gives the user an arm ([assign]) decides. Pinned with Swift by `conformance/design-arms.json`.
 */
suspend fun designArm(
  experiments: List<Experiment>,
  contract: String,
  context: DecisionContext,
  assign: suspend (Experiment) -> String?,
): DesignArm? {
  for (e in experiments) {
    if (e.arms.none { contract in it.designs } || !LocalBucketing.running(e, context)) continue
    val id = runCatching { assign(e) }.getOrNull() ?: continue
    val arm = e.arms.firstOrNull { it.id == id } ?: continue
    return DesignArm(e.id, arm.id, arm.designs[contract])
  }
  return null
}

/** Whether [context] matches these conditions: platform, locale, app version and segments. */
fun Conditions.matches(context: DecisionContext): Boolean {
  if (platforms.isNotEmpty() && context.platform !in platforms) return false
  if (locales.isNotEmpty() && locales.none { context.locale == it || context.locale.startsWith("$it-") }) return false
  minAppVersion?.let { if (context.appVersion < it) return false }
  maxAppVersion?.let { if (context.appVersion > it) return false }
  if (!context.segments.containsAll(segments)) return false
  if (excludeSegments.any { it in context.segments }) return false
  return true
}
