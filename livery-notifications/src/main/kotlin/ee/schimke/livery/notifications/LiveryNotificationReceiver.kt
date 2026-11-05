package ee.schimke.livery.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ee.schimke.composeai.rcwidget.launcher.RcLauncherWidgets
import ee.schimke.livery.client.ActionArguments
import ee.schimke.livery.instantiate.WidgetTap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives taps on a Livery notification body: subclass it, declare it in the manifest (not
 * exported), and build each tap's `PendingIntent` with [tapBroadcast]. A tap arrives in [onTap]
 * with the contract action and its arguments, off the main thread, inside `goAsync`.
 *
 * A tap that opens the app must be an activity, which a broadcast receiver may not start:
 * use `RcLauncherWidgets.actionActivity(context, launchIntent, notificationId, actionId)` for it.
 */
abstract class LiveryNotificationReceiver : BroadcastReceiver() {
  protected abstract suspend fun onTap(context: Context, notificationId: Int, tap: WidgetTap)

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != RcLauncherWidgets.ACTION_WIDGET_ACTION) return
    val tap = runCatching { RcLauncherWidgets.tapOf(intent) }.getOrNull() ?: return
    // The static payload is the contract action; the metadata, evaluated when tapped, its arguments.
    val action = tap.staticPayload ?: return
    val args = ActionArguments.parse(tap.metadata?.actionPayload) ?: return
    val result = goAsync()
    scope.launch {
      try { onTap(context, tap.widgetId, WidgetTap(action, args)) } finally { result.finish() }
    }
  }

  companion object {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The `PendingIntent` for tap [actionId] ([action]) on notification [notificationId], sent to [receiver]. */
    fun tapBroadcast(
      context: Context,
      receiver: Class<out LiveryNotificationReceiver>,
      notificationId: Int,
      actionId: Int,
      action: String,
    ): PendingIntent = RcLauncherWidgets.actionBroadcast(context, receiver, notificationId, actionId, action)
  }
}
