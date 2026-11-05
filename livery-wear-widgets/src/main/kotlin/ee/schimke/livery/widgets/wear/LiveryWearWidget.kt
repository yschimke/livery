@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.livery.widgets.wear

import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.glance.wear.GlanceWearWidget
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.WearWidgetData
import androidx.glance.wear.WearWidgetDocument
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.core.WearWidgetRawContent
import ee.schimke.composeai.rcplayer.edit.RcDisplay
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.composeai.rcwidget.RcWearContainerType
import ee.schimke.composeai.rcwidget.RcWearFrame
import ee.schimke.livery.client.CacheOnly
import ee.schimke.livery.client.EventSink
import ee.schimke.livery.client.PlanResult
import ee.schimke.livery.client.RenderPlan
import ee.schimke.livery.client.TemplateQuery
import ee.schimke.livery.client.ImageResolver
import ee.schimke.livery.instantiate.CachedImages
import ee.schimke.livery.instantiate.InstantiateResult
import ee.schimke.livery.instantiate.WidgetTap
import ee.schimke.livery.protocol.PlayerCapabilities
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.Target
import ee.schimke.livery.protocol.TemplateEvent
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A widget's container on this watch, and the query its template is chosen with: the container
 * type is the template's `wear:small` / `wear:large` target, its content size the size it fits.
 */
data class WearWidgetSlot(val instanceId: Int, val frame: RcWearFrame, val caps: PlayerCapabilities) {
  fun query(contract: String, designId: String? = null): TemplateQuery =
    TemplateQuery(contract, caps, widthDp = frame.widthDp.toInt(), heightDp = frame.heightDp.toInt(), designId = designId)

  companion object {
    fun containerOf(type: RcWearContainerType): String = when (type) {
      RcWearContainerType.SMALL -> CONTAINER_SMALL
      RcWearContainerType.LARGE -> CONTAINER_LARGE
    }

    const val CONTAINER_SMALL: String = "wear:small"
    const val CONTAINER_LARGE: String = "wear:large"
  }
}

/**
 * A Glance Wear widget drawn from a Livery template.
 *
 * For each render the host asks for: [plan] chooses the template and data for the widget's
 * container (usually `SlotPlanner.forAppData` / `forDecision` with [WearWidgetSlot.query], over a
 * repository whose blobs the phone synced or an LTE watch fetched; never waiting on the network);
 * the template is instantiated for this container on this watch (values and images written in,
 * the container's padding, corners and fill from the host, density folded in, the Wear widget
 * profile's rewrites); and its taps become `PendingIntent`s from [tapIntent], one per distinct tap.
 *
 * A template that can't be drawn renders [fallback] instead, which is never nothing.
 *
 * Subclass once per container type, and declare a `GlanceWearWidgetService` for each in the
 * manifest with a `wearwidget-provider` descriptor naming that type, as Glance Wear requires.
 */
abstract class LiveryWearWidget : GlanceWearWidget() {
  /** The template and data for this widget, or a fallback. */
  protected abstract suspend fun plan(context: Context, slot: WearWidgetSlot): PlanResult

  /**
   * The app's image resolver. A render draws only images already on the watch; one that lacks an
   * image draws [fallback], fetches it in the background, and asks the host to render again.
   */
  protected abstract fun images(context: Context): ImageResolver

  /**
   * The `PendingIntent` a tap sends: usually a broadcast or an activity in the app that runs
   * [tap]'s contract action ([ee.schimke.livery.client.ActionRouter]) with its arguments. Each
   * needs its own request code or data so the system keeps them apart; [index] is unique per
   * widget render.
   */
  protected abstract fun tapIntent(context: Context, instanceId: Int, index: Int, tap: WidgetTap): PendingIntent

  /** What to draw when no template can be (no blob yet, bad data, a template the host can't play). */
  protected abstract fun fallback(context: Context, slot: WearWidgetSlot, reason: String): WearWidgetData

  /**
   * What this host plays: Glance Wear's profile, a baked dark theme (a widget can't take the app's
   * live theme), and this [container]. Override to raise the level or budget for a known watch.
   */
  protected open fun capabilities(context: Context, container: String): PlayerCapabilities =
    PlayerCapabilities(
      Surfaces.WEAR_WIDGET, maxRcApiLevel = 7, profiles = setOf(PROFILE), livePush = false,
      themes = setOf(Target.baked(dark = true)), container = container, maxBytes = DEFAULT_MAX_BYTES,
    )

  /** The container's fill for this widget (a state tint), or null for the template's own. */
  protected open fun containerArgb(context: Context, slot: WearWidgetSlot): Int? = null

  protected open val events: EventSink = EventSink { }

  override suspend fun provideWidgetData(context: Context, params: WearWidgetParams): WearWidgetData {
    val frame = frameOf(params)
    val slot = WearWidgetSlot(params.instanceId.id, frame, capabilities(context, WearWidgetSlot.containerOf(frame.type)))
    // Planning downloads nothing here (CacheOnly): a template not on the watch yet is fetched
    // after this render, like a missing image at capture.
    val cacheOnly = CacheOnly()
    val plan = try {
      withContext(cacheOnly) { plan(context, slot) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "widget ${slot.instanceId}: plan failed", e)
      return fallback(context, slot, e.message ?: "plan failed")
    }
    return when (plan) {
      is PlanResult.Fallback -> {
        retryAfter(context, params) { cacheOnly.misses > 0 && cacheOnly.fetchMissed() }
        fallback(context, slot, plan.reason)
      }
      is PlanResult.Ready -> TemplateData(slot, plan.plan)
    }
  }

  /**
   * The instantiated template. Instantiation runs at capture, where Glance Wear hands over the
   * frame it records for this widget; any failure there still draws [fallback].
   */
  private inner class TemplateData(private val slot: WearWidgetSlot, private val plan: RenderPlan) : WearWidgetData {
    override suspend fun captureRawContent(context: Context, params: WearWidgetParams, isPreview: Boolean): WearWidgetRawContent {
      // Glance Wear's own capture of an empty container: its header (canvas, density, profile)
      // and extras are exactly what this widget's document carries.
      val empty = WearWidgetDocument(WearWidgetBrush.Companion) {}.captureRawContent(context, params, isPreview)
      val header = RcDocumentCodec.decode(empty.rcDocument, RcWireLimits()).header
      val resources = context.resources
      val display = RcDisplay(resources.displayMetrics.density, resources.configuration.fontScale)
      val images = CachedImages(images(context))
      val result = images.instantiator.instantiateForWear(
        plan.template.bytes, plan.bindings, slot.frame, display,
        header = header, containerArgb = containerArgb(context, slot), maxBytes = slot.caps.maxBytes,
      )
      val ok = when (result) {
        is InstantiateResult.Failed -> {
          Log.w(TAG, "widget ${slot.instanceId}: ${result.reason}")
          events.emit(event(TemplateEvent.Type.Fallback, result.reason))
          // An image not on the watch yet: fetch it after this render, then draw the template.
          if (!isPreview) retryAfter(context, params) { images.misses.isNotEmpty() && images.fetchMissed() }
          return fallback(context, slot, result.reason).captureRawContent(context, params, isPreview)
        }
        is InstantiateResult.Ok -> result
      }
      val extras = Bundle(empty.extras)
      if (ok.taps.isNotEmpty()) {
        val intents = Bundle()
        ok.taps.forEachIndexed { i, tap -> intents.putParcelable(i.toString(), tapIntent(context, slot.instanceId, i, tap)) }
        extras.putBundle(PENDING_INTENTS, intents)
      }
      // No Impression: handing the host a document isn't drawing it, and the host doesn't say
      // when the widget is on screen (PROTOCOL.md §8).
      return WearWidgetRawContent(ok.bytes, extras, empty.widgetTitle)
    }

    private fun event(type: TemplateEvent.Type, detail: String?) = TemplateEvent(
      type, System.currentTimeMillis(), plan.decision?.placement, plan.decision?.decisionId,
      plan.feedItem?.id, plan.template.entry.designId, plan.template.entry.sha256, detail = detail,
    )
  }

  /** Runs [fetch] off this render, and asks the host to render [params]' widget again when it got something. */
  private fun retryAfter(context: Context, params: WearWidgetParams, fetch: suspend () -> Boolean) {
    val app = context.applicationContext
    scope.launch {
      if (runCatching { fetch() }.getOrDefault(false)) runCatching { triggerUpdate(app, params.instanceId) }
    }
  }

  companion object {
    /**
     * The extras key under which Glance Wear's host finds a widget's `PendingIntent`s, by index
     * (Glance Wear's internal `WearWidgetCapture.PENDING_INTENT_KEY`).
     */
    const val PENDING_INTENTS: String = "pending_intents"

    /** The profile a Wear widget template targets. */
    const val PROFILE: String = "glance-wear"

    /** A Wear widget document's comfortable size. */
    const val DEFAULT_MAX_BYTES: Long = 100_000
    private const val TAG = "LiveryWearWidget"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The host's frame for [params], as rc-widget-core reads it. */
    fun frameOf(params: WearWidgetParams): RcWearFrame = RcWearFrame(
      type = RcWearContainerType.ofGlance(params.containerType) ?: RcWearContainerType.LARGE,
      widthDp = params.widthDp,
      heightDp = params.heightDp,
      horizontalPaddingDp = params.horizontalPaddingDp,
      verticalPaddingDp = params.verticalPaddingDp,
      cornerRadiusDp = params.cornerRadiusDp,
    )
  }
}
