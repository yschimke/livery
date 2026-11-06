package ee.schimke.livery.sentry

import ee.schimke.livery.protocol.TemplateEvent
import io.sentry.Breadcrumb
import io.sentry.SentryLevel
import kotlin.test.Test
import kotlin.test.assertEquals

class SentryBreadcrumbTest {
  @Test fun tapsAreUserClicksAndFailuresAreRaised() {
    val crumbs = mutableListOf<Breadcrumb>()
    val sink = SentryBreadcrumbEventSink { crumbs += it }
    sink.emit(TemplateEvent(TemplateEvent.Type.Action, 1, "home.top", designId = "card", action = "expand", args = mapOf("section" to "specs")))
    sink.emit(TemplateEvent(TemplateEvent.Type.Fallback, 2, detail = "c/card@1: no template"))
    sink.emit(TemplateEvent(TemplateEvent.Type.Error, 3, action = "open", detail = "url not allowed"))

    val (tap, fallback, error) = crumbs
    assertEquals(listOf("user", "ui.click", "livery expand", SentryLevel.INFO), listOf(tap.type, tap.category, tap.message, tap.level))
    assertEquals(mapOf("placement" to "home.top", "design_id" to "card", "action" to "expand", "arg_section" to "specs"), tap.data)
    assertEquals(listOf("info", "livery", "livery_fallback", SentryLevel.WARNING), listOf(fallback.type, fallback.category, fallback.message, fallback.level))
    assertEquals(SentryLevel.ERROR, error.level)
  }

  @Test fun aFailingSdkDoesNotThrow() {
    SentryBreadcrumbEventSink { error("not initialised") }.emit(TemplateEvent(TemplateEvent.Type.Impression, 1))
  }
}
