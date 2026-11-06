package ee.schimke.livery.sentry

import androidx.compose.ui.Modifier
import ee.schimke.livery.client.AnalyticsEvents
import ee.schimke.livery.client.EventSink
import ee.schimke.livery.client.RenderPlan
import ee.schimke.livery.client.slotTag
import ee.schimke.livery.protocol.TemplateEvent
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.compose.SentryModifier.sentryTag

/**
 * Livery's events as Sentry breadcrumbs, so an issue shows which templates the user saw and tapped
 * before it: a template tap is a `user` crumb in category `ui.click` (as Sentry's own tap crumbs
 * are), the rest are `info` crumbs in category `livery`, at `error` level for `Error` and `warning`
 * for `Fallback`. Data is [AnalyticsEvents.params]: placement, design, declared action arguments,
 * duration and experiment arms, never anything the contract doesn't declare.
 *
 * ```
 * val events = eventSinks(SentryBreadcrumbEventSink(), AnalyticsEventSink(…))
 * ```
 */
class SentryBreadcrumbEventSink(
  private val add: (Breadcrumb) -> Unit = { Sentry.addBreadcrumb(it) },
) : EventSink {
  override fun emit(event: TemplateEvent) {
    runCatching { add(LiverySentry.breadcrumb(event)) }
  }
}

object LiverySentry {
  fun breadcrumb(e: TemplateEvent): Breadcrumb = Breadcrumb(e.atEpochMs).apply {
    val tap = e.type == TemplateEvent.Type.Action
    type = if (tap) "user" else "info"
    category = if (tap) "ui.click" else "livery"
    message = if (tap) "livery ${e.action}" else AnalyticsEvents.name(e.type)
    level = when (e.type) {
      TemplateEvent.Type.Error -> SentryLevel.ERROR
      TemplateEvent.Type.Fallback -> SentryLevel.WARNING
      else -> SentryLevel.INFO
    }
    origin = "livery"
    for ((k, v) in AnalyticsEvents.params(e)) setData(k, v)
  }
}

/**
 * Sentry's tag for each template slot (`Modifier.sentryTag(plan.slotTag)`), so Sentry's Compose tap
 * tracking names the slot a tap landed on. Needs `sentry-compose` and Compose in the app; kept apart
 * from [LiverySentry] so breadcrumbs work without either:
 *
 * ```
 * LiveryHost(…, slotModifier = SentryCompose.slotModifier)
 * ```
 */
object SentryCompose {
  val slotModifier: (RenderPlan) -> Modifier = { Modifier.sentryTag(it.slotTag) }
}
