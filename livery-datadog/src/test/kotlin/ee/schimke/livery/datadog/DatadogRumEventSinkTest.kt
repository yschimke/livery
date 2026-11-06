package ee.schimke.livery.datadog

import com.datadog.android.rum.RumActionType
import com.datadog.android.rum.RumErrorSource
import com.datadog.android.rum.RumMonitor
import ee.schimke.livery.protocol.TemplateEvent
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

class DatadogRumEventSinkTest {
  /** A RumMonitor that records the calls it gets. */
  private class Recorded {
    val calls = mutableListOf<Pair<String, List<Any?>>>()
    val monitor = Proxy.newProxyInstance(RumMonitor::class.java.classLoader, arrayOf(RumMonitor::class.java)) { _, m, args ->
      calls += m.name to args.orEmpty().toList(); null
    } as RumMonitor
  }

  @Test fun tapsAreTapActionsAndFailuresAreErrors() {
    val r = Recorded()
    val sink = DatadogRumEventSink { r.monitor }
    sink.emit(TemplateEvent(TemplateEvent.Type.Action, 1, "home.top", designId = "card", action = "expand", args = mapOf("section" to "specs"), experiments = mapOf("look" to "b")))
    sink.emit(TemplateEvent(TemplateEvent.Type.Error, 2, action = "open", detail = "missing argument 'route'"))
    sink.emit(TemplateEvent(TemplateEvent.Type.Leave, 3, designId = "card", durationMs = 1_200))

    val (tap, error, leave) = r.calls
    assertEquals("addAction" to listOf(RumActionType.TAP, "livery expand",
      mapOf("livery.placement" to "home.top", "livery.design_id" to "card", "livery.action" to "expand", "livery.arg_section" to "specs", "livery.exp_look" to "b")), tap)
    assertEquals("addError", error.first)
    assertEquals(listOf("livery error: open: missing argument 'route'", RumErrorSource.SOURCE, null), error.second.take(3))
    assertEquals("addAction" to listOf(RumActionType.CUSTOM, "livery_leave", mapOf("livery.design_id" to "card", "livery.duration_ms" to 1_200L)), leave)
  }

  @Test fun noSdkNoProblem() {
    DatadogRumEventSink { error("Datadog isn't initialised") }.emit(TemplateEvent(TemplateEvent.Type.Impression, 1))
  }
}
