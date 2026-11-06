package ee.schimke.livery.amplitude

import ee.schimke.livery.client.AnalyticsEvents
import ee.schimke.livery.protocol.TemplateEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveryAmplitudeTest {
  @Test fun onlyExposuresSetArms() {
    val exposure = TemplateEvent(TemplateEvent.Type.Exposure, 1, designId = "card", experiments = mapOf("look" to "b"))
    assertEquals(mapOf("exp_look" to "b"), LiveryAmplitude.armProperties(AnalyticsEvents.name(exposure.type), AnalyticsEvents.params(exposure)))
    val tap = exposure.copy(type = TemplateEvent.Type.Action, action = "expand")
    assertEquals(emptyMap(), LiveryAmplitude.armProperties(AnalyticsEvents.name(tap.type), AnalyticsEvents.params(tap)))
  }
}
