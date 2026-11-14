package ee.schimke.livery.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import ee.schimke.composeai.rcplayer.compose.RcDisplay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import ee.schimke.livery.client.DesignSummary
import ee.schimke.composeai.rcplayer.compose.RcPreparation
import ee.schimke.livery.client.PlanResult
import ee.schimke.livery.client.TemplateRepository

/**
 * A slot whose content Livery decides: [plan] is one of `SlotPlanner.forAppData`, `forDecision`
 * or `forFeedItem`, re-run whenever [key] changes (new data, a new decision, another item, a
 * chosen design: everything [plan] reads from the app) and whenever the repository's
 * [TemplateRepository.revision] does (a refreshed catalog, an install). While
 * it runs, and when it falls back, the slot draws [fallback]: the app's native version, or nothing.
 *
 * With a [slotId] that names this slot on the host (`home.top`, `home-feed`), the slot remembers
 * its answer per [key] in [LiveryHost.resources]: when it comes back (scrolled away and back, a
 * screen reopened), or after [LiveryResources.prepareSlot] with the same id and key, it draws that
 * answer on its first frame instead of [fallback], and replans behind it. Without one it starts
 * from [fallback] every time.
 *
 * A new answer replaces what the slot shows only once its template is ready to draw
 * ([LiveryResources.readyToDraw]: prepared off the main thread, fonts loaded), so the template
 * draws complete on the frame it appears and composition never prepares it.
 */
@Composable
fun LiverySlot(
  key: Any?,
  host: LiveryHost,
  modifier: Modifier = Modifier,
  /**
   * Drawn while the slot plans (`reason` null) and when it falls back (the reason). By default the
   * host's [LiveryHost.placeholder] while it plans, and nothing when it falls back.
   */
  fallback: @Composable (reason: String?) -> Unit = { reason ->
    if (reason == null) host.placeholder(LiveryPlaceholder(LocalLiverySlotId.current))
  },
  slotId: String? = null,
  plan: suspend () -> PlanResult,
) {
  // A refreshed catalog or an install can change the answer, so the slot replans on either.
  val revision by host.repository.revision.collectAsState()
  // The display the template is prepared for, recorded so a host's warm-up prepares the same one.
  val display = rememberLiveryDisplay()
  SideEffect { host.resources.display = display }
  // Plans, and readies the answer's template to draw: what the slot waits for before it shows it.
  suspend fun planned(): PlanResult = plan().let { planned ->
    if (planned !is PlanResult.Ready) return@let planned
    val template = planned.plan.template
    // Readied as the host prepares: in the background on its context, or (a host that prepares in
    // composition) only its fonts, the document left for the card's own first composition.
    when (val preparation = host.preparation) {
      is RcPreparation.Background -> host.resources.readyToDraw(template, display, preparation.context)
      else -> runCatching { host.resources.typefaces(template) }
    }
    // A template whose bytes the player cannot play draws the app's fallback, never nothing.
    if (host.resources.cannotPlay(template)) PlanResult.Fallback("${template.entry.designId} cannot be played") else planned
  }
  // A slot that already had an answer at this revision (scrolled back, reopened) draws it at once
  // rather than its fallback for a frame; it still replans, and a new answer replaces it. One with
  // none, before the screen's first frame, plans off the main thread and waits for it here, within
  // the host's first-frame budget, so the first frame has its card rather than its fallback.
  // A window that has drawn already (other content first, then this slot) is past its first frame.
  if (windowHasDrawn()) host.resources.firstFrameDrawn = true
  val first = remember(key, host, slotId) {
    val last = slotId?.let { host.resources.lastPlan(it, key, revision) }
    val early = if (last == null) host.resources.beforeFirstFrame(host.firstFrameBudgetMillis) { planned() } else null
    val answer = last ?: early?.let { host.resources.awaitBeforeFirstFrame(host.firstFrameBudgetMillis, it) }
    FirstAnswer(mutableStateOf(answer), early, revision)
  }
  val state = first.state
  LaunchedEffect(state, revision) {
    // The plan started for the first frame, when it is for this revision, rather than a second one.
    val result = first.early?.takeIf { first.revision == revision }?.await() ?: planned()
    slotId?.let { host.resources.keepPlan(it, key, revision, result) }
    state.value = result
  }
  MarkFirstFrameDrawn(host.resources)
  val result = state.value
  // The slot's id reaches what it draws: the placeholder, its template's.
  // A template that finds it cannot play as it composes (a host that prepares in composition) says
  // so here, and the slot draws the app's fallback: for this answer, and for this key when it is
  // shown again at this revision.
  val cannotPlay = remember(state, revision) {
    { reason: String ->
      val fallback = PlanResult.Fallback(reason)
      slotId?.let { host.resources.keepPlan(it, key, revision, fallback) }
      state.value = fallback
    }
  }
  androidx.compose.runtime.CompositionLocalProvider(LocalLiverySlotId provides slotId, LocalLiveryCannotPlay provides cannotPlay) {
    when (val r = result) {
      null -> fallback(null)
      is PlanResult.Fallback -> fallback(r.reason)
      is PlanResult.Ready -> LiveryTemplate(r.plan, host, modifier)
    }
  }
}

/**
 * The designs a picker offers for [contract], refreshed with the catalog and after an install or
 * uninstall ([TemplateRepository.revision]), or when [key] changes.
 */
@Composable
fun rememberDesigns(repository: TemplateRepository, contract: String, key: Any? = null): List<DesignSummary> {
  val revision by repository.revision.collectAsState()
  val designs by produceState(emptyList<DesignSummary>(), repository, contract, key, revision) { value = repository.designs(contract) }
  return designs
}

/**
 * A plain design list: title, author, whether it is installed, and its first preview drawn by
 * [preview] (the app's image loader for `PreviewRef.url`). Apps with their own design system draw
 * [rememberDesigns] themselves; this is the minimal default.
 */
@Composable
fun DesignPicker(
  designs: List<DesignSummary>,
  onChoose: (DesignSummary) -> Unit,
  modifier: Modifier = Modifier,
  preview: @Composable (DesignSummary) -> Unit = {},
) {
  Column(modifier) {
    for (d in designs) {
      Row(Modifier.clickable { onChoose(d) }) {
        preview(d)
        BasicText(
          buildString {
            append(d.design.title)
            d.design.author?.let { append(" · ").append(it) }
            append(if (d.installed) " · installed" else " · ${d.downloadBytes / 1024} KB")
          },
        )
      }
    }
  }
}

/**
 * The display this composition draws on, as the player prepares a template for it: what to hand
 * [LiveryResources.warm] or [LiveryResources.preload] so the template they prepare is the one a
 * slot here looks up.
 */
@Composable
fun rememberLiveryDisplay(): RcDisplay {
  val density = LocalDensity.current
  return remember(density.density, density.fontScale) { RcDisplay(density.density, density.fontScale) }
}

/** A slot's answer, and the plan its first frame started (and may have waited for) at [revision]. */
private class FirstAnswer(
  val state: androidx.compose.runtime.MutableState<PlanResult?>,
  val early: kotlinx.coroutines.Deferred<PlanResult>?,
  val revision: Long,
)
