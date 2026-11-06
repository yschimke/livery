package ee.schimke.livery.client

import ee.schimke.livery.protocol.ActionKinds
import ee.schimke.livery.protocol.Decision
import ee.schimke.livery.protocol.PlayerCapabilities
import ee.schimke.livery.protocol.Presentation
import ee.schimke.livery.protocol.TemplateEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * A placement the app shows as a secondary screen (PROTOCOL.md §8.2): its [contract], the
 * presentations it [allows] (never [Presentation.Inline]), and the one used when a decision asks
 * for none. The app owns this list, so a server can never show a full screen where the app only
 * allows a sheet.
 */
data class ScreenPlacement(
  val placement: String,
  val contract: String,
  val allows: Set<Presentation>,
  val default: Presentation = allows.first(),
) {
  init {
    require(allows.isNotEmpty() && Presentation.Inline !in allows) { "$placement: a screen placement allows dialog, sheet or fullscreen" }
    require(default in allows) { "$placement: the default $default isn't allowed" }
  }
}

/** The one gate for screens (PROTOCOL.md §8.2), pinned with Swift by `conformance/screens.json`. */
object ScreenRules {
  /**
   * A screen opened from a slot or by the app is level 1; a screen may open one more (level 2);
   * a screen at level 2 can't open another.
   */
  const val MAX_DEPTH: Int = 2

  data class Outcome(val presentation: Presentation?, val problem: String?)

  /** How a screen at [depth] of [placement] shows when [requested] ([Presentation.Inline]: none asked). */
  fun presentation(placement: ScreenPlacement?, name: String, requested: Presentation, depth: Int): Outcome = when {
    placement == null -> Outcome(null, "$name isn't a screen placement")
    depth > MAX_DEPTH -> Outcome(null, "a screen opened from a screen can't open another")
    requested == Presentation.Inline -> Outcome(placement.default, null)
    requested !in placement.allows -> Outcome(null, "${requested.name.lowercase()} isn't allowed for $name")
    else -> Outcome(requested, null)
  }
}

/** A screen on show: what it draws, how, and how deep (1: opened from a slot or by the app). */
class PresentedScreen internal constructor(
  val id: Long,
  val placement: String,
  val presentation: Presentation,
  val plan: RenderPlan,
  val depth: Int,
)

/**
 * The app's secondary screens (PROTOCOL.md §8.2): interstitials it shows at its own moments
 * ([maybeShow]) and screens a template's `present` action opens ([present]), at most two deep.
 * [stack] is what the platform host draws (`LiveryScreenHost` in Compose, `.liveryScreens` in
 * SwiftUI), the top last.
 *
 * Opening a screen never waits on the network: it plans inside [CacheOnly], so a template or
 * asset not on the device means no screen, and what it would have downloaded is fetched in
 * [downloads] for next time (skipped without one; the repository's refresh prefetches screen
 * [targets] anyway). Overlapping requests can't stack screens: a screen commits only if the stack
 * is still what it expected (empty for an interstitial or a slot's screen, exactly its parent for
 * a screen's screen), else it is discarded. Where a screen's data comes from, in order:
 *
 * 1. the opening decision's `screens` for that action (an ad's detail screen ships with the ad);
 * 2. [appData] for the placement and the action's declared arguments (the app's own data);
 * 3. [decider] for the placement (local rules or the app's decision service).
 *
 * An interstitial uses 3 then 2. A decision from [LocalRulesDecider] is counted for its frequency
 * cap when it shows.
 */
class LiveryScreens(
  private val planner: SlotPlanner,
  private val decider: Decider,
  private val context: suspend () -> DecisionContext,
  placements: List<ScreenPlacement>,
  /** The player this app draws a screen with; its container is set from the presentation. */
  private val caps: (Presentation) -> PlayerCapabilities,
  private val events: EventSink,
  private val clock: Clock,
  private val appData: suspend (placement: String, args: Map<String, String>) -> JsonObject? = { _, _ -> null },
  private val frequency: LocalRulesDecider? = decider as? LocalRulesDecider,
  private val downloads: CoroutineScope? = null,
) {
  private val placements = placements.associateBy { it.placement }
  private val shown = MutableStateFlow<List<PresentedScreen>>(emptyList())
  private var nextId = 1L

  companion object {
    /**
     * The players screens draw with, one per presentation [placements] allow: add them to the
     * repository's targets, so screen templates are prefetched and can open without the network.
     */
    fun targets(placements: List<ScreenPlacement>, caps: (Presentation) -> PlayerCapabilities): List<PlayerCapabilities> =
      Presentation.entries.filter { p -> placements.any { p in it.allows } }.map { caps(it).copy(container = it.container) }
  }

  /** The screens on show, bottom first. */
  val stack: StateFlow<List<PresentedScreen>> = shown.asStateFlow()

  /**
   * Shows [placement] as an interstitial if its rules (or app data) have something for it now, and
   * no screen is already showing. True when one is shown.
   */
  suspend fun maybeShow(placement: String): Boolean {
    if (shown.value.isNotEmpty()) return false
    val decision = runCatching { decider.decide(placement, context()) }.getOrNull()
    if (decision == null) {
      val data = appData(placement, emptyMap()) ?: return false
      return show(placement, Presentation.Inline, 1) { q -> planner.forAppData(q, data) }
    }
    return show(placement, decision.presentation, 1, decision) { q -> planner.forDecision(q, decision) }
  }

  /** The `present` action handler: opens the screen [action] names, from the template of [from]. */
  suspend fun present(from: RenderPlan, action: String, args: Map<String, String>): Boolean {
    val spec = from.contract.actions[action]
    val target = spec?.placement
    if (spec?.kind != ActionKinds.PRESENT || target == null) return refuse(target ?: action, from.decision, "$action doesn't name a screen placement")
    val depth = (shown.value.indexOfFirst { it.plan === from } + 1) + 1
    from.decision?.screens?.get(action)?.let { screen ->
      val d = from.decision
      val decision = Decision(
        placement = target,
        decisionId = "${d.decisionId}>$action",
        template = screen.template,
        data = screen.data,
        tracking = d.tracking,
        experiments = d.experiments,
        presentation = screen.presentation,
      )
      return show(target, screen.presentation, depth, decision, from) { q -> planner.forDecision(q, decision) }
    }
    appData(target, ActionGate.check(from.contract, action, args).args)?.let { data ->
      return show(target, Presentation.Inline, depth, null, from) { q -> planner.forAppData(q, data) }
    }
    val decision = runCatching { decider.decide(target, context()) }.getOrNull()
      ?: return refuse(target, from.decision, "nothing to show for $target")
    return show(target, decision.presentation, depth, decision, from) { q -> planner.forDecision(q, decision) }
  }

  /**
   * Closes [screen] and any screen it opened, reporting a `Dismiss` with [reason]: `action` (the
   * template's own `dismiss`), `system` (back, a scrim tap) or `replaced`.
   */
  fun dismiss(screen: PresentedScreen, reason: String) {
    var closed: List<PresentedScreen> = emptyList()
    shown.update { stack ->
      val i = stack.indexOfFirst { it.id == screen.id }
      if (i < 0) stack else { closed = stack.drop(i); stack.take(i) }
    }
    for (s in closed.asReversed()) events.emit(event(TemplateEvent.Type.Dismiss, s.plan, s.placement, reason))
  }

  /** The screen [plan] is drawn in, if it is one. */
  fun screenOf(plan: RenderPlan): PresentedScreen? = shown.value.firstOrNull { it.plan === plan }

  /**
   * Handlers for an [ActionRouter]: `present`, and `dismiss`, which closes the screen it was
   * tapped in, or calls [dismissInline] for a slot's template.
   */
  fun handlers(
    dismissInline: suspend (RenderPlan, String, Map<String, String>) -> Unit = { _, _, _ -> },
  ): Map<String, suspend (RenderPlan, String, Map<String, String>) -> Unit> = mapOf(
    ActionKinds.PRESENT to { plan, action, args -> present(plan, action, args) },
    ActionKinds.DISMISS to { plan, action, args -> screenOf(plan)?.let { dismiss(it, "action") } ?: dismissInline(plan, action, args) },
  )

  private suspend fun show(
    placement: String,
    requested: Presentation,
    depth: Int,
    decision: Decision? = null,
    from: RenderPlan? = null,
    plan: suspend (TemplateQuery) -> PlanResult,
  ): Boolean {
    val outcome = ScreenRules.presentation(placements[placement], placement, requested, depth)
    val presentation = outcome.presentation ?: return refuse(placement, decision ?: from?.decision, outcome.problem!!)
    val contract = placements.getValue(placement).contract
    val query = TemplateQuery(contract, caps(presentation).copy(container = presentation.container))
    val cache = CacheOnly()
    // A screen opens now or not at all; what it would have downloaded comes down afterwards, off
    // this path.
    val result = withContext(cache) { plan(query) }
    if (cache.misses > 0) downloads?.launch { runCatching { cache.fetchMissed() } }
    val ready = result as? PlanResult.Ready ?: return false
    val screen = PresentedScreen(nextId++, placement, presentation, ready.plan, depth)
    // Commit only onto the stack this screen was planned for: nothing else opened meanwhile, and
    // its parent is still showing.
    while (true) {
      val stack = shown.value
      val expected = stack.size == depth - 1 && (depth == 1 || stack.last().plan === from)
      if (!expected) return false
      if (shown.compareAndSet(stack, stack + screen)) break
    }
    decision?.let { frequency?.recordImpression(it, clock.nowEpochMs()) }
    return true
  }

  private fun refuse(placement: String, d: Decision?, reason: String): Boolean {
    events.emit(TemplateEvent(TemplateEvent.Type.Fallback, clock.nowEpochMs(), placement, d?.decisionId, detail = reason, experiments = d?.experiments.orEmpty()))
    return false
  }

  private fun event(type: TemplateEvent.Type, p: RenderPlan, placement: String, detail: String?) = TemplateEvent(
    type, clock.nowEpochMs(), p.decision?.placement ?: placement, p.decision?.decisionId, p.feedItem?.id,
    p.template.entry.designId, p.template.entry.sha256, detail = detail, experiments = p.experiments,
  )
}
