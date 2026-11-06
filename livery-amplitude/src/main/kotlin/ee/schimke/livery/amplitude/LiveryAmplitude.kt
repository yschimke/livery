package ee.schimke.livery.amplitude

import com.amplitude.core.Amplitude
import com.amplitude.core.events.Identify
import ee.schimke.livery.client.AnalyticsEvents
import ee.schimke.livery.client.AnalyticsSink

/**
 * Livery's events in Amplitude, in [AnalyticsEvents]' shape (`livery_action` with `action` and
 * `arg_*`, `livery_leave` with `duration_ms`, …):
 *
 * ```
 * val events = AnalyticsEventSink(AmplitudeAnalyticsSink(amplitude))
 * ```
 *
 * With [userProperties], each exposure also sets its arm as a user property (`exp_<experiment>`),
 * so Amplitude can split every event, not only Livery's, by arm.
 */
class AmplitudeAnalyticsSink(
  private val amplitude: Amplitude,
  private val userProperties: Boolean = true,
) : AnalyticsSink {
  override fun log(name: String, params: Map<String, Any>) {
    amplitude.track(name, params)
    if (userProperties) LiveryAmplitude.armProperties(name, params).takeIf { it.isNotEmpty() }?.let { arms ->
      amplitude.identify(Identify().apply { arms.forEach { (k, v) -> set(k, v) } })
    }
  }
}

object LiveryAmplitude {
  /** The user properties an event sets: an exposure's `exp_<experiment>` → arm, nothing else. */
  fun armProperties(name: String, params: Map<String, Any>): Map<String, String> =
    if (name != AnalyticsEvents.PREFIX + "exposure") emptyMap()
    else params.filterKeys { it.startsWith("exp_") }.mapValues { it.value.toString() }
}
