package ee.schimke.livery.instantiate

import okio.ByteString.Companion.toByteString
import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.InputSpec
import ee.schimke.livery.protocol.ListSpec
import ee.schimke.livery.protocol.ValueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplateInspectorTest {
  private val tile = javaClass.getResource("/demo-tile.rc")!!.readBytes().toByteString()

  @Test fun readsNamedValuesAndActions() {
    val f = TemplateInspector.facts(tile)!!
    assertEquals(setOf("light.kitchen.state", "light.kitchen.is_on"), f.reads)
    assertEquals(setOf("ha"), f.emits)
    assertNull(TemplateInspector.facts(byteArrayOf(1, 2, 3).toByteString()))
  }

  @Test fun aDataBoundImageIsARead() {
    val d = RcDocumentCodec.decode(tile.toByteArray(), RcWireLimits())
    val url = "livery:value/hero".encodeToByteArray()
    val withImage = d.copy(operations = listOf(RcBitmapData(9000, 1, 1, 0, RcBitmapData.ENCODING_URL, url)) + d.operations)
    val bytes = RcDocumentCodec.encode(withImage).toByteString()
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

  @Test fun aListCountIsAnInt() {
    // Rename the tile's string value to a list's count: the binder always writes an int there.
    val d = RcDocumentCodec.decode(tile.toByteArray(), RcWireLimits())
    val withCount = RcDocumentCodec.encode(d.copy(operations = d.operations.map {
      if (it is RcNamedVariable && it.name == "USER:light.kitchen.state") it.copy(name = "USER:light.kitchen.count") else it
    })).toByteString()
    val c = Contract("ha/tile", 1, lists = mapOf("light.kitchen" to ListSpec(emptyMap(), max = 2)))
    assertEquals(listOf("reads 'light.kitchen.count' as a string, but ha/tile@1 declares int"), TemplateInspector.typeProblems(withCount, c))
  }

  @Test fun aDataBoundImageMustBeAnImage() {
    val d = RcDocumentCodec.decode(tile.toByteArray(), RcWireLimits())
    val bytes = RcDocumentCodec.encode(d.copy(operations = listOf(RcBitmapData(9000, 1, 1, 0, RcBitmapData.ENCODING_URL, "livery:value/hero".encodeToByteArray())) + d.operations)).toByteString()
    val c = Contract("ha/tile", 1, inputs = mapOf("hero" to InputSpec(ValueType.String)))
    assertEquals(listOf("reads 'hero' as an image, but ha/tile@1 declares string"), TemplateInspector.typeProblems(bytes, c))
    assertEquals(emptyList(), TemplateInspector.typeProblems(bytes, c.copy(inputs = mapOf("hero" to InputSpec(ValueType.Image)))))
  }

  @Test fun aValueUsedAsBothAStringAndAnImageConflicts() {
    // The tile's string `light.kitchen.state` also drawn as a data-bound image.
    val d = RcDocumentCodec.decode(tile.toByteArray(), RcWireLimits())
    val bytes = RcDocumentCodec.encode(d.copy(operations = listOf(RcBitmapData(9000, 1, 1, 0, RcBitmapData.ENCODING_URL, "livery:value/light.kitchen.state".encodeToByteArray())) + d.operations)).toByteString()
    assertEquals(setOf(RcNamedVariable.STRING_TYPE, RcNamedVariable.IMAGE_TYPE), TemplateInspector.facts(bytes)!!.types.getValue("light.kitchen.state"))
    val asString = Contract("ha/tile", 1, inputs = mapOf("light.kitchen.state" to InputSpec(ValueType.String)))
    assertEquals(listOf("reads 'light.kitchen.state' as an image, but ha/tile@1 declares string"), TemplateInspector.typeProblems(bytes, asString))
  }
}
