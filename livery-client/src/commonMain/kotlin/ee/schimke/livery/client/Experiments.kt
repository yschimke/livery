package ee.schimke.livery.client

import ee.schimke.livery.protocol.Arm
import ee.schimke.livery.protocol.Conditions
import ee.schimke.livery.protocol.Experiment
import ee.schimke.livery.protocol.Manifest
import ee.schimke.livery.protocol.TemplateEvent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 */
class Experiments(
  private val manifest: suspend () -> Manifest?,
  private val context: suspend () -> DecisionContext,
  private val assigner: ExperimentAssigner = LocalBucketing,
  private val events: EventSink = EventSink { },
) {
  private val exposed = mutableSetOf<Pair<String, String>>()
  private val lock = Mutex()

  /** The user's arm of [experimentId], or null when it isn't running for them. */
  suspend fun arm(experimentId: String): String? {
    val e = manifest()?.experiments?.firstOrNull { it.id == experimentId } ?: return null
    return armOf(e, context())?.id
  }

  /**
   * The design [contract] (`id@major`) draws for this user, from the first running experiment whose
   * arm picks one, or null to select as usual. [DesignArm.experiment] and [DesignArm.arm] go on the
   * plan so every event carries them.
   */
  suspend fun designFor(contract: String): DesignArm? {
    val ctx = context()
    for (e in manifest()?.experiments.orEmpty()) {
      val arm = armOf(e, ctx) ?: continue
      arm.designs[contract]?.let { return DesignArm(e.id, arm.id, it) }
    }
    return null
  }

  /** Reports the exposure once per experiment and arm for this [Experiments]. */
  suspend fun exposed(experiment: String, arm: String, atEpochMs: Long, placement: String? = null, designId: String? = null) {
    val first = lock.withLock { exposed.add(experiment to arm) }
    if (first) events.emit(TemplateEvent(TemplateEvent.Type.Exposure, atEpochMs, placement, designId = designId, experiments = mapOf(experiment to arm)))
  }

  private suspend fun armOf(e: Experiment, ctx: DecisionContext): Arm? {
    if (!LocalBucketing.running(e, ctx)) return null
    val id = runCatching { assigner.assign(e, ctx) }.getOrNull() ?: return null
    return e.arms.firstOrNull { it.id == id }
  }
}

data class DesignArm(val experiment: String, val arm: String, val designId: String)

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
