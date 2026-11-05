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
import ee.schimke.livery.client.EventSink
import ee.schimke.livery.client.PlanResult
import ee.schimke.livery.client.TemplateQuery
import ee.schimke.livery.instantiate.InstantiateResult
import ee.schimke.livery.instantiate.TemplateInstantiator
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

/** A widget's cell, and the query its template is chosen with. */
data class WidgetSlot(val widgetId: Int, val widthDp: Int, val heightDp: Int, val caps: PlayerCapabilities) {
  fun query(contract: String, designId: String? = null): TemplateQuery =
    TemplateQuery(contract, caps, widthDp = widthDp, heightDp = heightDp, designId = designId)
}

/**
 * A launcher AppWidget drawn from a Livery template (Android 16+, where the launcher plays Remote
 * Compose documents in `RemoteViews` draw instructions; older launchers get [fallback]).
 *
 * For each widget, on update and on resize: [plan] chooses the template and data for the widget's
 * cell (usually `SlotPlanner.forAppData` / `forDecision` with [WidgetSlot.query]); the template is
 * instantiated for that size (values and images written in, taps made numeric) and published.
 * Taps arrive as [WidgetTap]s in [onTap], with the contract action and its arguments.
 *
 * Updates run one at a time per process, off the main thread, inside `goAsync`.
 */
abstract class LiveryLauncherWidgetProvider : AppWidgetProvider() {
  /** The template and data for this widget, or a fallback. */
  protected abstract suspend fun plan(context: Context, slot: WidgetSlot): PlanResult

  /** Writes values and images in; usually `TemplateInstantiator(imageResolver)`. */
  protected abstract fun instantiator(context: Context): TemplateInstantiator

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
    scope.launch {
      try { updates.withLock { for (id in ids) update(context, manager, id) } } finally { result.finish() }
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

  private suspend fun update(context: Context, manager: AppWidgetManager, id: Int) {
    val views = runCatching {
      // Android 16 is the first whose launcher plays Remote Compose layouts in draw instructions.
      if (Build.VERSION.SDK_INT >= RcLauncherWidgets.MIN_HOST_API && RcLauncherWidgets.isSupported) render(context, manager, id)
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
  private suspend fun render(context: Context, manager: AppWidgetManager, id: Int): RemoteViews? {
    val size = RcWidgetSizing.slotOf(manager, id)
    val slot = WidgetSlot(id, size.widthDp, size.heightDp, capabilities(context))
    val plan = when (val p = plan(context, slot)) {
      is PlanResult.Fallback -> return fallback(context, id, p.reason)
      is PlanResult.Ready -> p.plan
    }
    val density = context.resources.displayMetrics.density
    val result = instantiator(context).instantiate(
      plan.template.bytes, plan.bindings,
      imageWidthPx = (slot.widthDp * density).toInt(), imageHeightPx = (slot.heightDp * density).toInt(),
      maxBytes = slot.caps.maxBytes, numericActions = true,
    )
    val ok = when (result) {
      is InstantiateResult.Failed -> return fallback(context, id, result.reason)
      is InstantiateResult.Ok -> result
    }
    val taps = ok.actions.mapValues { (actionId, action) -> RcLauncherWidgets.actionBroadcast(context, javaClass, id, actionId, action) }
    // No Impression: publishing isn't drawing, and a launcher doesn't say when (or whether) the
    // widget is on screen (PROTOCOL.md §8).
    return RcLauncherWidgets.remoteViews(ok.bytes, taps)
  }

  companion object {
    /** About what launchers accept comfortably for a widget document. */
    const val DEFAULT_MAX_BYTES: Long = 50_000
    private const val TAG = "LiveryWidget"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val updates = Mutex()

    /** Re-renders every widget of [provider], e.g. when its data or catalog changes. */
    fun updateAll(context: Context, provider: Class<out LiveryLauncherWidgetProvider>) {
      val manager = AppWidgetManager.getInstance(context)
      val ids = manager.getAppWidgetIds(ComponentName(context, provider))
      if (ids.isEmpty()) return
      context.sendBroadcast(Intent(context, provider).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids))
    }
  }
}
