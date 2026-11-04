package ee.schimke.livery.compose

import ee.schimke.composeai.rcplayer.runtime.RcHostActionValue
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.composeai.rcplayer.runtime.RcPlayerEvent
import ee.schimke.livery.client.Bindings
import ee.schimke.livery.protocol.NamedValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerValuesTest {
  @Test fun bindingsBecomePlayerValues() {
    val v = Bindings(mapOf(
      "title" to NamedValue.Str("Hi"), "count" to NamedValue.I(3), "at" to NamedValue.L(9L),
      "level" to NamedValue.F(0.5f), "on" to NamedValue.B(true), "accent" to NamedValue.Color(0xFF112233),
      "hero" to NamedValue.Image("app:hero"),
    )).toPlayerValues()
    assertEquals(RcNamedValue.Text("Hi"), v["title"])
    assertEquals(RcNamedValue.Integer(3), v["count"])
    assertEquals(RcNamedValue.LongValue(9L), v["at"])
    assertEquals(RcNamedValue.FloatValue(0.5f), v["level"])
    assertEquals(RcNamedValue.BooleanValue(true), v["on"])
    assertEquals(RcNamedValue.Color(0xFF112233.toInt()), v["accent"])
    assertNull(v["hero"], "images are drawn by URL, not pushed")
  }

  @Test fun hostNamedActionsBecomeTemplateActions() {
    assertEquals(TemplateAction("cta", mapOf("route" to "/premium")), RcPlayerEvent.HostNamedAction("cta", RcHostActionValue.TextValue("""{"route":"/premium"}""")).toTemplateAction())
    assertEquals(TemplateAction("open", mapOf("value" to "/a")), RcPlayerEvent.HostNamedAction("open", RcHostActionValue.TextValue("/a")).toTemplateAction())
    assertEquals(TemplateAction("pick", mapOf("value" to "2")), RcPlayerEvent.HostNamedAction("pick", RcHostActionValue.IntegerValue(2)).toTemplateAction())
    assertEquals(TemplateAction("dismiss", emptyMap()), RcPlayerEvent.HostNamedAction("dismiss", RcHostActionValue.None).toTemplateAction())
    assertNull(RcPlayerEvent.HostNamedAction("x", RcHostActionValue.TextValue("""{"nested":{"a":1}}""")).toTemplateAction())
    assertNull(RcPlayerEvent.HostAction(7).toTemplateAction())
  }
}
