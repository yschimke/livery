package ee.schimke.livery.instantiate

import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplateInspectorTest {
  private val tile = javaClass.getResource("/demo-tile.rc")!!.readBytes()

  @Test fun readsNamedValuesAndActions() {
    val f = TemplateInspector.facts(tile)!!
    assertEquals(setOf("light.kitchen.state", "light.kitchen.is_on"), f.reads)
    assertEquals(setOf("ha"), f.emits)
    assertNull(TemplateInspector.facts(byteArrayOf(1, 2, 3)))
  }

  @Test fun aDataBoundImageIsARead() {
    val d = RcDocumentCodec.decode(tile, RcWireLimits())
    val url = "livery:value/hero".encodeToByteArray()
    val withImage = d.copy(operations = listOf(RcBitmapData(9000, 1, 1, 0, RcBitmapData.ENCODING_URL, url)) + d.operations)
    val bytes = RcDocumentCodec.encode(withImage)
    assertTrue("hero" in TemplateInspector.facts(bytes)!!.declaredReads)
    val problems = TemplateInspector.problems(bytes, listOf("light.kitchen.state", "light.kitchen.is_on"), listOf("ha"))
    assertEquals(listOf("reads [hero] that its sidecar doesn't declare"), problems)
  }
}
