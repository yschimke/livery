package ee.schimke.livery.widgets.launcher

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import ee.schimke.composeai.rcwidget.launcher.RcLauncherWidgets
import ee.schimke.composeai.rcwidget.launcher.RcWidgetSizing
import ee.schimke.livery.client.ActionArguments
import ee.schimke.livery.client.CacheOnly
import ee.schimke.livery.client.EventSink
import ee.schimke.livery.client.PlanResult
import ee.schimke.livery.client.TemplateQuery
import ee.schimke.livery.client.ImageResolver
import ee.schimke.livery.instantiate.CachedImages
import ee.schimke.livery.instantiate.InstantiateResult
import ee.schimke.livery.instantiate.WidgetTap
import ee.schimke.livery.protocol.PlayerCapabilities
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.TemplateEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A widget's cell, and the query its template is chosen with. */
data class WidgetSlot(
  val widgetId: Int,
  val widthDp: Int,
  val heightDp: Int,
  val caps: PlayerCapabilities,
  /**
   * True when the provider asks again because the chosen design couldn't be drawn: [query] then
   * ignores its `designId`, so the plan falls back to the automatic design, never to nothing.
   */
  val automaticOnly: Boolean = false,
) {
  internal var namedDesign = false
    private set

  fun query(contract: String, designId: String? = null): TemplateQuery {
    if (designId != null) namedDesign = true
    return TemplateQuery(contract, caps, widthDp = widthDp, heightDp = heightDp, designId = designId.takeUnless { automaticOnly })
  }
}

/**
 * A launcher AppWidget drawn from a Livery template (Android 16+, where the launcher plays Remote
 * Compose documents in `RemoteViews` draw instructions; older launchers get [fallback]).
 *
 * For each widget, on update and on resize: [plan] chooses the template and data for the widget's
 * cell (usually `SlotPlanner.forAppData` / `forDecision` with [WidgetSlot.query]); the template is
 * instantiated for that size (values and images written in, taps made numeric) and published.
 * Nothing in an update waits on the network: images come from the device ([CachedImages]).
 * Taps arrive as [WidgetTap]s in [onTap], with the contract action and its arguments.
 *
 * Updates run one at a time per process, off the main thread, inside `goAsync`.
 */
abstract class LiveryLauncherWidgetProvider : AppWidgetProvider() {
  /** The template and data for this widget, or a fallback. */
  protected abstract suspend fun plan(context: Context, slot: WidgetSlot): PlanResult

  /**
   * The app's image resolver. A render draws only images already on the device; one that lacks an
   * image draws [fallback], fetches it in the background, and renders again.
   */
  protected abstract fun images(context: Context): ImageResolver

  /** A tap on a published widget. */
  protected abstract suspend fun onTap(context: Context, widgetId: Int, tap: WidgetTap)

  /** What this launcher plays; override to raise the level or budget for a known host. */
  protected open fun capabilities(context: Context): PlayerCapabilities =
    PlayerCapabilities(Surfaces.LAUNCHER_WIDGET, maxRcApiLevel = 7, profiles = setOf("widgets"), livePush = false, maxBytes = DEFAULT_MAX_BYTES)

  /** What to show when no template can be drawn (older launcher, offline first run, bad data). */
  protected open fun fallback(context: Context, widgetId: Int, reason: String): RemoteViews? = null

  protected open val events: EventSink = EventSink { }

  override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
    val result = goAsync()
    val provider = javaClass
    scope.launch {
      try {
        val retries = Retries()
        updates.withLock { for (id in ids) update(context, manager, id, retries) }
        // Every widget is published (template or fallback) before anything is downloaded. What
        // the renders lacked is fetched now, outside the update lock but inside this broadcast,
        // which keeps the process alive; a widget whose downloads arrive is updated again.
        withTimeoutOrNull(RETRY_BUDGET_MS) { retries.run(context.applicationContext, provider) }
      } finally {
        result.finish()
      }
    }
  }

  override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
    // A resize: the template lays out for its cell, so instantiate again at the new size.
    onUpdate(context, manager, intArrayOf(id))
  }

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != RcLauncherWidgets.ACTION_WIDGET_ACTION) return super.onReceive(context, intent)
    val tap = runCatching { RcLauncherWidgets.tapOf(intent) }.getOrNull() ?: return
    // The tap's static payload is the contract action; its metadata, evaluated when tapped, the arguments.
    val action = tap.staticPayload ?: return
    val args = ActionArguments.parse(tap.metadata?.actionPayload) ?: return
    val result = goAsync()
    scope.launch {
      try { onTap(context, tap.widgetId, WidgetTap(action, args)) } finally { result.finish() }
    }
  }

  private suspend fun update(context: Context, manager: AppWidgetManager, id: Int, retries: Retries) {
    val views = runCatching {
      // Android 16 is the first whose launcher plays Remote Compose layouts in draw instructions.
      if (Build.VERSION.SDK_INT >= RcLauncherWidgets.MIN_HOST_API && RcLauncherWidgets.isSupported) render(context, manager, id, retries)
      else fallback(context, id, "launcher can't play Remote Compose")
    }
      .onFailure {
        Log.w(TAG, "widget $id failed", it)
        events.emit(TemplateEvent(TemplateEvent.Type.Error, System.currentTimeMillis(), detail = it.message))
      }
      .getOrElse { fallback(context, id, it.message ?: "failed") }
    views?.let { manager.updateAppWidget(id, it) }
  }

  @RequiresApi(RcLauncherWidgets.MIN_HOST_API)
  private suspend fun render(context: Context, manager: AppWidgetManager, id: Int, retries: Retries): RemoteViews? {
    val size = RcWidgetSizing.slotOf(manager, id)
    val slot = WidgetSlot(id, size.widthDp, size.heightDp, capabilities(context))
    val first = attempt(context, slot, retries)
    if (first is Attempt.Drawn) return first.views
    // A design the app chose that can't be drawn (a missing image, over budget) falls back to the
    // automatic design, never to nothing. A decision's or feed item's template stays exact.
    if (first is Attempt.Failed && first.appOwned && slot.namedDesign) {
      val automatic = attempt(context, slot.copy(automaticOnly = true), retries)
      if (automatic is Attempt.Drawn) return automatic.views
    }
    return fallback(context, id, (first as Attempt.Failed).reason)
  }

  private sealed interface Attempt {
    class Drawn(val views: RemoteViews) : Attempt
    class Failed(val reason: String, val appOwned: Boolean) : Attempt
  }

  @RequiresApi(RcLauncherWidgets.MIN_HOST_API)
  private suspend fun attempt(context: Context, slot: WidgetSlot, retries: Retries): Attempt {
    val id = slot.widgetId
    // Planning downloads nothing here (CacheOnly): a template not on the device yet is fetched
    // after this update, like a missing image below.
    val cacheOnly = CacheOnly()
    val planned = withContext(cacheOnly) { plan(context, slot) }
    // Fetched whether or not the plan found something to draw: an older cached or bundled version
    // may play now while the newer one it skipped is still owed.
    if (cacheOnly.misses > 0) retries.add(id) { cacheOnly.fetchMissed() }
    val plan = when (val p = planned) {
      is PlanResult.Fallback -> return Attempt.Failed(p.reason, appOwned = false)
      is PlanResult.Ready -> p.plan
    }
    val density = context.resources.displayMetrics.density
    val images = CachedImages(images(context))
    val result = images.instantiator.instantiate(
      plan.template.bytes, plan.bindings,
      imageWidthPx = (slot.widthDp * density).toInt(), imageHeightPx = (slot.heightDp * density).toInt(),
      maxBytes = slot.caps.maxBytes, numericActions = true,
    )
    val ok = when (result) {
      is InstantiateResult.Failed -> {
        // An image not on the device yet: fetch it after this update, then draw the template.
        if (images.misses.isNotEmpty()) retries.add(id) { images.fetchMissed() }
        return Attempt.Failed(result.reason, appOwned = plan.decision == null && plan.feedItem == null)
      }
      is InstantiateResult.Ok -> result
    }
    val taps = ok.actions.mapValues { (actionId, action) -> RcLauncherWidgets.actionBroadcast(context, javaClass, id, actionId, action) }
    // No Impression: publishing isn't drawing, and a launcher doesn't say when (or whether) the
    // widget is on screen (PROTOCOL.md §8).
    return Attempt.Drawn(RcLauncherWidgets.remoteViews(ok.bytes, taps))
  }

  /** Downloads an update's renders skipped, by widget, run after every widget is published. */
  private class Retries {
    private val fetches = ArrayList<Pair<Int, suspend () -> Boolean>>()

    fun add(id: Int, fetch: suspend () -> Boolean) { fetches += id to fetch }

    /**
     * Runs every fetch, each on its own (a chosen design whose image is gone for good must not
     * hold back the automatic design's), then asks for one new update of each widget for which any
     * of them completed: that widget now has a design it can draw.
     */
    suspend fun run(context: Context, provider: Class<out AppWidgetProvider>) {
      if (fetches.isEmpty()) return
      val ok = fetches.map { (id, f) -> id to runCatching { f() }.getOrDefault(false) }
        .filter { it.second }.mapTo(LinkedHashSet()) { it.first }
      // A new broadcast, not onUpdate: this receiver's goAsync result is in use.
      if (ok.isNotEmpty()) requestUpdate(context, provider, ok.toIntArray())
    }
  }

  companion object {
    /** About what launchers accept comfortably for a widget document. */
    const val DEFAULT_MAX_BYTES: Long = 50_000

    /**
     * How long an update keeps its broadcast open to fetch what its renders lacked, after every
     * widget is published; anything slower waits for the next update.
     */
    const val RETRY_BUDGET_MS: Long = 7_000
    private const val TAG = "LiveryWidget"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val updates = Mutex()

    /** Re-renders every widget of [provider], e.g. when its data or catalog changes. */
    fun updateAll(context: Context, provider: Class<out LiveryLauncherWidgetProvider>) {
      val manager = AppWidgetManager.getInstance(context)
      val ids = manager.getAppWidgetIds(ComponentName(context, provider))
      if (ids.isNotEmpty()) requestUpdate(context, provider, ids)
    }

    private fun requestUpdate(context: Context, provider: Class<out AppWidgetProvider>, ids: IntArray) =
      context.sendBroadcast(Intent(context, provider).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids))
  }
}
