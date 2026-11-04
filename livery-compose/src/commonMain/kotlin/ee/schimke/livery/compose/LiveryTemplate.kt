package ee.schimke.livery.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.compose.RcImageLoader
import ee.schimke.composeai.rcplayer.compose.RcPlayerTheme
import ee.schimke.composeai.rcplayer.compose.RcTypefaceLoader
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.composeai.rcplayer.runtime.RcPlayerEvent
import ee.schimke.livery.client.ActionRouter
import ee.schimke.livery.client.Clock
import ee.schimke.livery.client.EventSink
import ee.schimke.livery.client.ImageResolver
import ee.schimke.livery.client.RenderPlan
import ee.schimke.livery.client.TemplateRepository
import ee.schimke.livery.protocol.TemplateEvent
import kotlinx.coroutines.delay
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
)

/**
 * Plays one [RenderPlan]. Values are in place before the first frame, so a template arrives showing
 * current data; later changes to [RenderPlan.bindings] are pushed without restarting the document,
 * so only updates animate. Reports [TemplateEvent.Type.Impression] after its first frame and
 * [TemplateEvent.Type.Viewable] once at least half of it has been on screen for a second.
 */
@Composable
fun LiveryTemplate(plan: RenderPlan, host: LiveryHost, modifier: Modifier = Modifier) {
  val bytes = plan.template.bytes
  val current by rememberUpdatedState(plan)
  val pushed = plan.bindings.toPlayerValues()
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

  // A template's fonts load before it draws, so text never reflows from a fallback face.
  val loader = typefaces ?: return
  Box(modifier.onGloballyPositioned { c ->
    val size = c.size
    val visible = c.boundsInWindow()
    val area = size.width.toFloat() * size.height
    tracker.fraction = if (area <= 0f) 0f else (visible.width * visible.height) / area
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
    host.events.emit(event(TemplateEvent.Type.Impression, plan, host))
  }
  LaunchedEffect(tracker) {
    while (true) {
      if (tracker.fraction >= VIEWABLE_FRACTION) {
        delay(VIEWABLE_MILLIS)
        if (tracker.fraction >= VIEWABLE_FRACTION) { host.events.emit(event(TemplateEvent.Type.Viewable, plan, host)); break }
      } else delay(POLL_MILLIS)
    }
  }
}

internal class VisibilityTracker {
  var fraction by mutableStateOf(0f)
}

private fun event(type: TemplateEvent.Type, plan: RenderPlan, host: LiveryHost) = TemplateEvent(
  type = type,
  atEpochMs = host.clock.nowEpochMs(),
  placement = plan.decision?.placement,
  decisionId = plan.decision?.decisionId,
  feedItemId = plan.feedItem?.id,
  designId = plan.template.entry.designId,
  sha256 = plan.template.entry.sha256,
)

private const val VIEWABLE_FRACTION = 0.5f
private const val VIEWABLE_MILLIS = 1_000L
private const val POLL_MILLIS = 250L
