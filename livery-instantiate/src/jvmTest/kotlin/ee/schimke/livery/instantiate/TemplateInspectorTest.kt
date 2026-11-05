package ee.schimke.livery.instantiate

import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.InputSpec
import ee.schimke.livery.protocol.ValueType
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

  @Test fun typesComeFromTheDocument() {
    val f = TemplateInspector.facts(tile)!!
    val right = Contract("ha/tile", 1, inputs = mapOf("light.kitchen.state" to InputSpec(ValueType.String), "light.kitchen.is_on" to InputSpec(ValueType.Bool)))
    assertEquals(emptyList(), TemplateInspector.typeProblems(tile, right))
    val wrong = right.copy(inputs = right.inputs + ("light.kitchen.state" to InputSpec(ValueType.Float)))
    assertEquals(listOf("reads 'light.kitchen.state' as a string, but ha/tile@1 declares float"), TemplateInspector.typeProblems(tile, wrong))
  }
}
