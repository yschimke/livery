package ee.schimke.livery.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.testTag
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
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
)

/**
 * Plays one [RenderPlan]. Values are in place before the first frame, so a template arrives showing
 * current data; later changes to [RenderPlan.bindings] are pushed without restarting the document,
 * so only updates animate. Reports [TemplateEvent.Type.Impression] after its first frame,
 * [TemplateEvent.Type.Viewable] once at least half of it has been on screen for an unbroken second
 * (Compose's `onVisibilityChanged`), and [TemplateEvent.Type.Leave] with the time it was at least
 * half on screen when it leaves composition or the slot shows another item.
 */
@Composable
fun LiveryTemplate(plan: RenderPlan, host: LiveryHost, modifier: Modifier = Modifier, extra: Bindings = Bindings.EMPTY) {
  val bytes = plan.template.bytes
  val current by rememberUpdatedState(plan)
  // [extra] are values the host writes (a screen's insets): pushed with the data, never part of the plan.
  val pushed = (plan.bindings + extra).toPlayerValues()
  val values = remember(bytes) { mutableStateMapOf<String, RcNamedValue>().apply { putAll(pushed) } }
  SideEffect {
    values.keys.toList().filter { it !in pushed }.forEach(values::remove)
    values.putAll(pushed)
  }
  val imageLoader = remember(bytes, host) {
    host.images?.let { LiveryImageLoader(it, { current.bindings }, host.fallbackImages) } ?: host.fallbackImages
  }
  val typefaces by produceState<RcTypefaceLoader?>(null, bytes, host) {
    value = runCatching { LiveryTypefaceLoader.forTemplate(plan.template, host.repository, host.fallbackTypefaces) }.getOrDefault(host.fallbackTypefaces)
  }
  val scope = rememberCoroutineScope()
  val tracker = remember(bytes, plan.decision?.decisionId, plan.feedItem?.id) { VisibilityTracker() }
  // Each tracker keeps its own item's plan: when the slot shows another item, the old tracker's
  // Leave must name the old item, not the plan that replaced it.
  SideEffect { tracker.plan = plan }

  // A template's fonts load before it draws, so text never reflows from a fallback face.
  val loader = typefaces ?: return
  Box(modifier.then(host.slotModifier(plan)).onVisibilityChanged(minFractionVisible = VIEWABLE_FRACTION) { visible ->
    tracker.visible(visible, host.clock.nowEpochMs())
  }) {
    RcComposePlayer(
      bytes = bytes,
      imageLoader = imageLoader,
      modifier = Modifier,
      theme = host.theme,
      namedValues = values,
      onEvent = { e: RcPlayerEvent -> e.toTemplateAction()?.let { a -> scope.launch { host.actions.dispatch(current, a.name, a.args) } } },
      typefaces = loader,
    )
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
  decisionId = plan.decision?.decisionId,
  feedItemId = plan.feedItem?.id,
  designId = plan.template.entry.designId,
  sha256 = plan.template.entry.sha256,
  experiments = plan.experiments,
)

private const val VIEWABLE_FRACTION = 0.5f
private const val VIEWABLE_MILLIS = 1_000L
