package ee.schimke.livery.notifications

import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import ee.schimke.composeai.rcwidget.launcher.RcLauncherWidgets
import ee.schimke.livery.client.CacheOnly
import ee.schimke.livery.client.EventSink
import ee.schimke.livery.client.ImageResolver
import ee.schimke.livery.client.PlanResult
import ee.schimke.livery.client.RenderPlan
import ee.schimke.livery.client.TemplateQuery
import ee.schimke.livery.instantiate.CachedImages
import ee.schimke.livery.instantiate.InstantiateResult
import ee.schimke.livery.protocol.PlayerCapabilities
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.TemplateEvent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext

/** A notification's expanded body, and the query its template is chosen with. */
data class NotificationSlot(
  val notificationId: Int,
  val widthDp: Int,
  val heightDp: Int,
  val caps: PlayerCapabilities,
  /**
   * True when the render asks again because the chosen design couldn't be drawn: [query] then
   * ignores its `designId`, so the plan falls back to the automatic design, never to nothing.
   */
  val automaticOnly: Boolean = false,
) {
  internal var namedDesign = false
    private set

  /** The query for this slot; [minor] is the contract minor the app implements (TemplateQuery.minor). */
  fun query(contract: String, designId: String? = null, minor: Int? = null): TemplateQuery {
    if (designId != null) namedDesign = true
    return TemplateQuery(contract, caps, widthDp = widthDp, heightDp = heightDp, designId = designId.takeUnless { automaticOnly }, minor = minor)
  }
}

/**
 * One render of a notification's body: [views] for `setCustomBigContentView`, or null with
 * [reason] when the template couldn't be drawn (post the notification without a custom body).
 */
class NotificationRender internal constructor(
  val views: RemoteViews?,
  val reason: String?,
  /** The template the body was drawn from, for the app's own events. */
  val plan: RenderPlan?,
  private val missed: List<suspend () -> Boolean>,
) {
  /** True when the render skipped something not on the device yet (a template or an image). */
  val hasMisses: Boolean get() = missed.isNotEmpty()

  /**
   * Downloads what this render lacked. True when anything arrived: render again and re-post the
   * notification (same id) to show it. Never called by [LiveryNotifications.render] itself, which
   * doesn't wait on the network.
   */
  suspend fun fetchMissed(): Boolean {
    var any = false
    for (fetch in missed) if (runCatching { fetch() }.getOrDefault(false)) any = true
    return any
  }
}

/**
 * Notifications drawn from Livery templates: a template for the [Surfaces.NOTIFICATION] surface,
 * instantiated for the notification's expanded body and handed to SystemUI as `RemoteViews` draw
 * instructions. Android 16 (API 36) and later play them; older devices get [NotificationRender]
 * with no views, and the app posts its plain notification.
 *
 * ```
 * val render = notifications.render(context, id, plan = { slot -> planner.forAppData(slot.query(CONTRACT), data) },
 *   tapIntent = { actionId, action -> LiveryNotificationReceiver.tapBroadcast(context, Receiver::class.java, id, actionId, action) })
 * render.views?.let { builder.setCustomBigContentView(it) } ?: builder.setStyle(BigTextStyle().bigText(text))
 * ```
 *
 * Since API 31 SystemUI nests every custom body in its own template, style or not, and Android 17
 * refuses a nested view that has draw instructions ("Cannot add a nested view that has
 * DrawInstructions"): SystemUI drops the notification, and a foreground service's crashes the app.
 * [NotificationRender.views] is therefore already wrapped for nesting
 * (`RcLauncherWidgets.notificationRemoteViews`); post it as it is, without wrapping or replacing
 * it. Leave the style off alongside it, so the custom body is the expanded view.
 *
 * Rendering never waits on the network: planning is [CacheOnly], images come from the device
 * ([CachedImages]), and what was missing is fetched by [NotificationRender.fetchMissed].
 */
class LiveryNotifications(
  private val images: ImageResolver,
  /**
   * What SystemUI plays. Its `maxBytes` bounds the *template* chosen and downloaded; SystemUI is
   * handed only the instantiated document, held to [documentMaxBytes].
   */
  val capabilities: PlayerCapabilities = DEFAULT_CAPABILITIES,
  private val documentMaxBytes: Long = DEFAULT_MAX_BYTES,
  private val events: EventSink = EventSink { },
) {
  /**
   * The body of notification [notificationId], [heightDp] tall (at most [MAX_EXPANDED_HEIGHT_DP],
   * which Android caps a custom expanded body at) and as wide as the shade's content. [plan]
   * chooses the template and data for the slot; [tapIntent] makes each tap's `PendingIntent`
   * (usually [LiveryNotificationReceiver.tapBroadcast]).
   */
  suspend fun render(
    context: Context,
    notificationId: Int,
    heightDp: Int = MAX_EXPANDED_HEIGHT_DP,
    plan: suspend (NotificationSlot) -> PlanResult,
    tapIntent: (actionId: Int, action: String) -> PendingIntent,
  ): NotificationRender {
    if (Build.VERSION.SDK_INT < RcLauncherWidgets.MIN_HOST_API || !RcLauncherWidgets.isSupported) {
      return NotificationRender(null, "SystemUI can't play Remote Compose", null, emptyList())
    }
    val metrics = context.resources.displayMetrics
    val widthDp = (metrics.widthPixels / metrics.density).roundToInt() - 2 * SIDE_INSET_DP
    val slot = NotificationSlot(notificationId, widthDp, heightDp.coerceAtMost(MAX_EXPANDED_HEIGHT_DP), capabilities)
    val missed = ArrayList<suspend () -> Boolean>()
    return try {
      val first = attempt(context, slot, plan, tapIntent, missed)
      if (first.views == null && first.appOwned && slot.namedDesign) {
        // A design the app chose that can't be drawn falls back to the automatic design, never
        // to nothing. A decision's or feed item's template stays exact.
        val automatic = attempt(context, slot.copy(automaticOnly = true), plan, tapIntent, missed)
        if (automatic.views != null) return automatic.toRender(missed)
      }
      if (first.views == null) {
        events.emit(TemplateEvent(TemplateEvent.Type.Fallback, System.currentTimeMillis(), detail = first.reason))
      }
      first.toRender(missed)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      events.emit(TemplateEvent(TemplateEvent.Type.Error, System.currentTimeMillis(), detail = e.message))
      NotificationRender(null, e.message ?: "failed", null, missed)
    }
  }

  private class Attempt(val views: RemoteViews?, val reason: String?, val plan: RenderPlan?, val appOwned: Boolean) {
    fun toRender(missed: List<suspend () -> Boolean>) = NotificationRender(views, reason, plan, missed)
  }

  @RequiresApi(RcLauncherWidgets.MIN_HOST_API)
  private suspend fun attempt(
    context: Context,
    slot: NotificationSlot,
    plan: suspend (NotificationSlot) -> PlanResult,
    tapIntent: (Int, String) -> PendingIntent,
    missed: MutableList<suspend () -> Boolean>,
  ): Attempt {
    val cacheOnly = CacheOnly()
    val planned = withContext(cacheOnly) { plan(slot) }
    // Owed whether or not the plan found something: an older cached or bundled version may play
    // now while the newer one it skipped is still to download.
    if (cacheOnly.misses > 0) missed += { cacheOnly.fetchMissed() }
    val ready = when (planned) {
      is PlanResult.Fallback -> return Attempt(null, planned.reason, null, appOwned = false)
      is PlanResult.Ready -> planned.plan
    }
    val density = context.resources.displayMetrics.density
    val cached = CachedImages(images)
    val result = cached.instantiator.instantiate(
      ready.template.bytes, ready.bindings,
      imageWidthPx = (slot.widthDp * density).roundToInt(), imageHeightPx = (slot.heightDp * density).roundToInt(),
      maxBytes = documentMaxBytes, numericActions = true,
    )
    val appOwned = ready.decision == null && ready.feedItem == null
    return when (result) {
      is InstantiateResult.Failed -> {
        if (cached.misses.isNotEmpty()) missed += { cached.fetchMissed() }
        Attempt(null, result.reason, ready, appOwned)
      }
      is InstantiateResult.Ok -> {
        val taps = result.actions.mapValues { (actionId, action) -> tapIntent(actionId, action) }
        // No Impression: posting isn't showing, and SystemUI doesn't say when the body is
        // expanded (PROTOCOL.md §8).
        // Wrapped so SystemUI's template can nest it on Android 17 (see the class docs).
        Attempt(RcLauncherWidgets.notificationRemoteViews(result.bytes.toByteArray(), taps), null, ready, appOwned)
      }
    }
  }

  companion object {
    /** Android's cap on a custom expanded notification body. */
    const val MAX_EXPANDED_HEIGHT_DP: Int = 256

    /** The shade's content inset each side, approximately. */
    const val SIDE_INSET_DP: Int = 32

    /** About what a notification's body should weigh, after the values are written in. */
    const val DEFAULT_MAX_BYTES: Long = 50_000

    /** A template, before instantiation. */
    const val DEFAULT_TEMPLATE_MAX_BYTES: Long = 500_000

    /** SystemUI's player: the widget profile, as for launcher widgets. */
    val DEFAULT_CAPABILITIES: PlayerCapabilities = PlayerCapabilities(
      Surfaces.NOTIFICATION, maxRcApiLevel = 7, profiles = setOf("widgets"), livePush = false, maxBytes = DEFAULT_TEMPLATE_MAX_BYTES,
    )
  }
}
