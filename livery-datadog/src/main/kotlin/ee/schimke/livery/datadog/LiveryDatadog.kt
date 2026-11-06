package ee.schimke.livery.datadog

import com.datadog.android.rum.GlobalRumMonitor
import com.datadog.android.rum.RumActionType
import com.datadog.android.rum.RumErrorSource
import com.datadog.android.rum.RumMonitor
import ee.schimke.livery.client.AnalyticsEvents
import ee.schimke.livery.client.EventSink
import ee.schimke.livery.protocol.TemplateEvent

/**
 * Livery's events in Datadog RUM, in the user's current RUM view:
 *
 * - a template tap (`Action`) is a `TAP` action named `livery <action>`, so it sits in the session
 *   beside the app's own taps, named by its contract action whichever design drew it;
 * - `Error` and `Fallback` are RUM errors (source `SOURCE`);
 * - `Impression`, `Viewable`, `Exposure`, `Leave` and `Dismiss` are `CUSTOM` actions (`livery_viewable`, …).
 *
 * Every one carries [AnalyticsEvents.params] as `livery.*` attributes: placement, design, the
 * declared action arguments (`livery.arg_*`), `livery.duration_ms`, and the experiment arms.
 *
 * ```
 * val events = eventSinks(DatadogRumEventSink(), AnalyticsEventSink(…))
 * ```
 */
class DatadogRumEventSink(
  private val monitor: () -> RumMonitor = { GlobalRumMonitor.get() },
) : EventSink {
  override fun emit(event: TemplateEvent) {
    val rum = runCatching(monitor).getOrNull() ?: return
    val attributes = LiveryDatadog.attributes(event)
    when (event.type) {
      TemplateEvent.Type.Action -> rum.addAction(RumActionType.TAP, LiveryDatadog.actionName(event), attributes)
      TemplateEvent.Type.Error, TemplateEvent.Type.Fallback ->
        rum.addError(LiveryDatadog.errorMessage(event), RumErrorSource.SOURCE, null, attributes)
      else -> rum.addAction(RumActionType.CUSTOM, AnalyticsEvents.name(event.type), attributes)
    }
  }
}

/** How Livery events are named and described in Datadog. */
object LiveryDatadog {
  const val ATTRIBUTE_PREFIX: String = "livery."

  fun actionName(e: TemplateEvent): String = "livery ${e.action ?: "action"}"

  fun errorMessage(e: TemplateEvent): String =
    "livery ${e.type.name.lowercase()}: " + listOfNotNull(e.action, e.detail).joinToString(": ").ifEmpty { "unknown" }

  fun attributes(e: TemplateEvent): Map<String, Any?> = AnalyticsEvents.params(e).mapKeys { ATTRIBUTE_PREFIX + it.key }
}
