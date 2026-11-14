package ee.schimke.livery.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.CompositionLocalProvider
import ee.schimke.composeai.rcplayer.compose.LocalRcDeferLayoutAnimations
import ee.schimke.composeai.rcplayer.compose.ExperimentalRcPlayerApi
import ee.schimke.composeai.rcplayer.compose.LocalRcFlattenLayout
import ee.schimke.composeai.rcplayer.compose.LocalRcPreparation
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.compose.RcPreparation
import ee.schimke.composeai.rcplayer.compose.RcImageLoader
import ee.schimke.composeai.rcplayer.compose.RcPlayerTheme
import ee.schimke.composeai.rcplayer.compose.RcTypefaceLoader
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.composeai.rcplayer.runtime.RcPlayerEvent
import ee.schimke.livery.client.ActionRouter
import ee.schimke.livery.client.Bindings
import ee.schimke.livery.client.Clock
import ee.schimke.livery.client.EventSink
import ee.schimke.livery.client.ImageResolver
import ee.schimke.livery.client.RenderPlan
import ee.schimke.livery.client.TemplateRepository
import ee.schimke.livery.client.slotTag
import ee.schimke.livery.protocol.NamedValue
import ee.schimke.livery.protocol.TemplateEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** What [LiveryTemplate] needs besides the plan: where actions, events, images and fonts go. */
class LiveryHost(
  val repository: TemplateRepository,
  val actions: ActionRouter,
  val events: EventSink,
  val clock: Clock,
  val images: ImageResolver? = null,
  val theme: RcPlayerTheme = RcPlayerTheme.System,
  val fallbackTypefaces: RcTypefaceLoader = RcTypefaceLoader.Default,
  val fallbackImages: RcImageLoader = RcImageLoader.Default,
  /**
   * What goes on the player's node, so tools that find a tapped node by its tag (Sentry, Datadog,
   * UI tests) attribute a tap to its slot: by default `Modifier.testTag(plan.slotTag)`. An
   * integration module can supply its own (`livery-sentry`'s `sentryTag`).
   */
  val slotModifier: (RenderPlan) -> Modifier = { Modifier.testTag(it.slotTag) },
  /**
   * Documents, fonts, images and slot answers kept in memory between the slots that use this host,
   * so a card after the first of its template draws complete on its first frame. Share one host
   * across a screen, and [LiveryResources.preload] what is coming.
   */
  val resources: LiveryResources = LiveryResources(repository, images, fallbackTypefaces, fallbackImages),
  /**
   * How the player prepares a template's document, configured for every [LiveryTemplate] on this
   * host and for any player nested inside one. By default [RcPreparation.Background] over
   * [resources]' document cache: composition only looks a template up, so a list that scrolls never
   * decodes a template on its main thread. A template's first card that nobody prepared
   * ([LiveryResources.warm], [LiveryResources.preload], or the [LiverySlot] that planned it) draws
   * nothing until it is ready, as it already waits for its fonts. [RcPreparation.InComposition]
   * prepares it in that card's composition instead, on the main thread.
   */
  val preparation: RcPreparation = RcPreparation.Background(resources.documents),
  /**
   * Whether a template that can only change through its data skips the lookahead pass behind
   * AndroidX's bounds animations until its data first changes (rc-players'
   * `LocalRcDeferLayoutAnimations`). That pass runs over every card on every frame a list scrolls;
   * a card's first data change still animates, one frame later. A template that animates, reads the
   * clock, scrolls, takes input or writes its own state keeps the pass regardless. False plays
   * every template exactly as AndroidX does, resizes included.
   */
  val deferLayoutAnimations: Boolean = true,
  /**
   * Whether a template's plain boxes, rows, columns and texts are laid out and drawn by their
   * container in one node (rc-players' experimental `LocalRcFlattenLayout`), rather than one
   * composable each: fewer layout nodes and less composition per card in a feed. Every tap target,
   * data-driven badge and custom component stays a component of its own, and the texts stay in the
   * semantics tree, and anything the flat layout cannot draw exactly as the components would (a
   * row aligned by baseline, a clip around a composed child, auto-sized text) stays on the
   * per-component path. Applies only to templates whose layout animations are deferred
   * ([deferLayoutAnimations]). Pass `false` to give every component its own composable.
   */
  val flattenLayout: Boolean = true,
  /**
   * How long, in all, a screen's slots and templates may hold its first frame for their work:
   * planning, preparing and fonts, which run off the main thread while it waits. A slower first
   * frame with its cards in it rather than a blank one that fills in: on Android the system's
   * starting window stays up meanwhile. Only before the screen's first frame; after it nothing
   * waits, so a scroll never does. What is not ready in time draws as it would without the wait
   * (a slot's fallback, a held height). 0 never waits. The browser cannot wait, whatever this is.
   */
  val firstFrameBudgetMillis: Long = DEFAULT_FIRST_FRAME_BUDGET_MILLIS,
  /**
   * Drawn in place of a card that cannot draw yet once the screen is up: unplanned content arriving
   * in a feed, a template's first card nobody prepared. It is held to the height the template last
   * drew at for this width, when it has drawn before, so the list does not move when the card
   * arrives: it is measured at exactly that size; without one it takes its own height (a
   * skeleton's). By default nothing is drawn there.
   */
  val placeholder: @Composable (LiveryPlaceholder) -> Unit = {},
)

/** Long enough to plan and prepare a screen of cards from disk, short of a noticeable delay. */
const val DEFAULT_FIRST_FRAME_BUDGET_MILLIS: Long = 150

/**
 * Plays one [RenderPlan]. Values are in place before the first frame, so a template arrives showing
 * current data; later changes to [RenderPlan.bindings] are pushed without restarting the document,
 * so only updates animate. Each template draws its first frame complete, with no blank or
 * fallback-font frame first, when its document, fonts and images are in [LiveryHost.resources]:
 * after any earlier card of the same template (fonts: of any template sharing them), or after a
 * [LiveryResources.preload]. Only a template's very first card, not preloaded, waits for its fonts. Reports [TemplateEvent.Type.Impression] after its first frame,
 * [TemplateEvent.Type.Viewable] once at least half of it has been on screen for an unbroken second
 * (Compose's `onVisibilityChanged`), and [TemplateEvent.Type.Leave] with the time it was at least
 * half on screen when it leaves composition or the slot shows another item.
 */
@OptIn(ExperimentalRcPlayerApi::class)
@Composable
fun LiveryTemplate(plan: RenderPlan, host: LiveryHost, modifier: Modifier = Modifier, extra: Bindings = Bindings.EMPTY) {
  // Platform work that waits for the app's first template to draw (Android: emoji, see LiveryEmoji).
  AfterFirstTemplateFrame()
  val bytes = plan.template.bytes
  val current by rememberUpdatedState(plan)
  // [extra] are values the host writes (a screen's insets): pushed with the data, never part of the plan.
  val pushed = (plan.bindings + extra).toPlayerValues()
  val values = remember(bytes) { mutableStateMapOf<String, RcNamedValue>().apply { putAll(pushed) } }
  SideEffect {
    values.keys.toList().filter { it !in pushed }.forEach(values::remove)
    values.putAll(pushed)
  }
  // Shared by every card of this template: decoding, linking, the layout tree and the state seed are
  // done once per template, not per card, and (by default) off the main thread. Each card's player
  // state is still its own.
  val display = rememberLiveryDisplay()
  SideEffect { host.resources.display = display }
  // Prepared for this display, or else as published if that is what was prepared ahead (a startup
  // before the display was known): the same pixels, on this frame, rather than none until the
  // rewrite is ready. The rewrite is still prepared, for the cards after this one.
  val preparedState = remember(bytes, host, display) {
    val cached = host.resources.preparedNow(plan.template, display)
      ?: host.resources.preparedNow(plan.template, display = null)
    if (cached == null && !host.resources.cannotPlay(plan.template)) host.resources.recordMiss()
    mutableStateOf(cached)
  }
  val background = host.preparation as? RcPreparation.Background
  val inComposition = background == null
  if (preparedState.value == null && background != null) {
    // On the host's preparation context, as the player itself would prepare it.
    LaunchedEffect(bytes, host, display) {
      preparedState.value = host.resources.prepareOrNull(plan.template, display, background.context)
    }
  }
  // [RcPreparation.InComposition]: the host chose to prepare here, on this frame.
  val preparedEarly = preparedState.value
    ?: if (inComposition) remember(bytes, host, display) { host.resources.preparedOrNull(plan.template, display) } else null
  // Keyed on the image values too, so a slot that shows another item's picture seeds it from memory.
  val imageValues = plan.bindings.values.filterValues { it is NamedValue.Image }
  val imageLoader = remember(bytes, host, imageValues) { host.resources.imageLoader { current.bindings } }
  // In memory already (an earlier card, a preload): drawn on this composition's frame. Otherwise read
  // once, and kept for every later card.
  val typefaceState = remember(bytes, host) { mutableStateOf(host.resources.typefacesNow(plan.template)) }
  LaunchedEffect(typefaceState) {
    if (typefaceState.value == null) {
      typefaceState.value = runCatching { host.resources.typefaces(plan.template) }.getOrDefault(host.fallbackTypefaces)
    }
  }
  // Before the screen's first frame, a card that is not ready yet (nobody prepared its template)
  // waits for it here, within the host's first-frame budget, and draws on that frame from memory.
  if (windowHasDrawn()) host.resources.firstFrameDrawn = true
  val readiedForFirstFrame = remember(bytes, host, display) {
    val ready = preparedState.value != null && typefaceState.value != null
    if (ready || host.resources.cannotPlay(plan.template)) false
    else host.resources.beforeFirstFrame(host.firstFrameBudgetMillis) {
      // On the host's preparation context, as the card's own preparation below would run.
      kotlinx.coroutines.coroutineScope {
        // A host that prepares in composition prepares the document there; only fonts load ahead.
        if (background != null) launch { prepareOrNull(plan.template, display, background.context) }
        launch { runCatching { typefaces(plan.template) } }
      }
    }
      ?.let { host.resources.awaitBeforeFirstFrame(host.firstFrameBudgetMillis, it) } != null
  }
  val typefaces = typefaceState.value
    ?: if (readiedForFirstFrame) host.resources.typefacesNow(plan.template) else null
  val scope = rememberCoroutineScope()
  val tracker = remember(bytes, plan.decisionId, plan.feedItem?.id) { VisibilityTracker() }
  // Each tracker keeps its own item's plan: when the slot shows another item, the old tracker's
  // Leave must name the old item, not the plan that replaced it.
  SideEffect { tracker.plan = plan }

  // A template's fonts load before it draws, so text never reflows from a fallback face; and it
  // draws only once it is prepared, which is never in composition under a background strategy.
  val loader = typefaces
  val prepared = preparedEarly ?: if (readiedForFirstFrame) host.resources.preparedNow(plan.template, display) else null
  MarkFirstFrameDrawn(host.resources)
  if (host.resources.cannotPlay(plan.template)) {
    // Not a document this player can play: no card, rather than an app that fails with it.
    Layout(modifier) { _, constraints -> layout(constraints.minWidth, constraints.minHeight) {} }
    return
  }
  if (loader == null || prepared == null) {
    // Nothing to draw yet (a template's first card nobody prepared): the host's placeholder, held to
    // the height the template last drew at for this width, so the list does not move when the card
    // arrives; without one, the placeholder's own height.
    val slotId = LocalLiverySlotId.current
    Layout(
      content = {
        host.placeholder(
          LiveryPlaceholder(slotId, plan.contract, plan.template.entry.designId),
        )
      },
      modifier = modifier,
    ) { measurables, constraints ->
      val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
      val last = host.resources.lastHeight(plan.template, width)
      val child =
        if (last != null) androidx.compose.ui.unit.Constraints.fixed(width, constraints.constrainHeight(last))
        else constraints.copy(minWidth = width, maxWidth = width)
      val placeables = measurables.map { it.measure(child) }
      val height = last ?: (placeables.maxOfOrNull { it.height } ?: 0)
      layout(width, constraints.constrainHeight(height)) { placeables.forEach { it.place(0, 0) } }
    }
    return
  }
  Box(modifier.then(host.slotModifier(plan)).onVisibilityChanged(minFractionVisible = VIEWABLE_FRACTION) { visible ->
    tracker.visible(visible, host.clock.nowEpochMs())
  }.onSizeChanged { host.resources.keepHeight(plan.template, it.width, it.height) }) {
    CompositionLocalProvider(
      LocalRcPreparation provides host.preparation,
      LocalRcDeferLayoutAnimations provides host.deferLayoutAnimations,
      LocalRcFlattenLayout provides host.flattenLayout,
    ) {
      RcComposePlayer(
        prepared = prepared,
        imageLoader = imageLoader,
        modifier = Modifier,
        theme = host.theme,
        namedValues = values,
        onEvent = { e: RcPlayerEvent -> e.toTemplateAction(current.contract)?.let { a -> scope.launch { host.actions.dispatch(current, a.name, a.args) } } },
        typefaces = loader,
      )
    }
  }
  LaunchedEffect(tracker) {
    withFrameNanos { }
    tracker.impressed = true
    host.events.emit(event(TemplateEvent.Type.Impression, plan, host))
  }
  // Viewable needs one unbroken second at or above the threshold: each time the template drops
  // below it the timer is cancelled, and it starts again from zero when it comes back.
  LaunchedEffect(tracker) {
    snapshotFlow { tracker.isVisible }
      .distinctUntilChanged()
      .collectLatest { visible ->
        if (visible && !tracker.viewed) {
          delay(VIEWABLE_MILLIS)
          tracker.viewed = true
          host.events.emit(event(TemplateEvent.Type.Viewable, plan, host))
        }
      }
  }
  // Leave: once per item, with how long it was in view, when it goes (scrolled away, the slot shows
  // another decision or item, the screen closes).
  DisposableEffect(tracker) {
    onDispose {
      val now = host.clock.nowEpochMs()
      val left = tracker.plan
      if (tracker.impressed && left != null) host.events.emit(event(TemplateEvent.Type.Leave, left, host).copy(durationMs = tracker.inViewMs(now)))
    }
  }
}

/** One rendered item's visibility: whether it is at least half on screen, and for how long in total. */
internal class VisibilityTracker {
  var isVisible by mutableStateOf(false)
    private set
  var viewed = false
  var impressed = false
  /** The plan of the item this tracker measures (its bindings may update; its item doesn't). */
  var plan: RenderPlan? = null
  private var since: Long? = null
  private var totalMs = 0L

  fun visible(visible: Boolean, nowMs: Long) {
    if (visible == isVisible) return
    if (visible) since = nowMs else since?.let { totalMs += (nowMs - it).coerceAtLeast(0); since = null }
    isVisible = visible
  }

  fun inViewMs(nowMs: Long): Long = totalMs + (since?.let { (nowMs - it).coerceAtLeast(0) } ?: 0)
}

private fun event(type: TemplateEvent.Type, plan: RenderPlan, host: LiveryHost) = TemplateEvent(
  type = type,
  atEpochMs = host.clock.nowEpochMs(),
  placement = plan.decision?.placement,
  decisionId = plan.decisionId,
  feedItemId = plan.feedItem?.id,
  designId = plan.template.entry.designId,
  sha256 = plan.template.entry.sha256,
  experiments = plan.experiments,
)

private const val VIEWABLE_FRACTION = 0.5f
private const val VIEWABLE_MILLIS = 1_000L
