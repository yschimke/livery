package ee.schimke.livery.instantiate

import okio.ByteString.Companion.toByteString
import okio.ByteString.Companion.encodeUtf8
import ee.schimke.livery.client.Bindings
import ee.schimke.livery.protocol.NamedValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Over a real values-free document (a tile reading `light.kitchen.state` and `.is_on`). */
class TemplateInstantiatorTest {
  private val tile = javaClass.getResource("/demo-tile.rc")!!.readBytes().toByteString()
  private val noImages = TemplateInstantiator { _, _, _ -> null }

  @Test fun theTemplateReadsItsNamedValues() {
    assertEquals(setOf("light.kitchen.is_on", "light.kitchen.state"), TemplateInstantiator.reads(tile))
  }

  @Test fun everyBoundValueIsWrittenIn() = runBlocking {
    val values = Bindings(mapOf("light.kitchen.state" to NamedValue.Str("Brightly on"), "light.kitchen.is_on" to NamedValue.I(1)))
    val out = assertIs<InstantiateResult.Ok>(noImages.instantiate(tile, values, 100, 100)).bytes
    assertEquals(emptySet(), TemplateInstantiator.reads(out), "nothing left for a host to push")
    assertTrue(out.string(Charsets.ISO_8859_1).contains("Brightly on"))
  }

  @Test fun unboundValuesStayNamed() = runBlocking {
    val out = assertIs<InstantiateResult.Ok>(noImages.instantiate(tile, Bindings(mapOf("light.kitchen.state" to NamedValue.Str("Off"))), 100, 100)).bytes
    assertEquals(setOf("light.kitchen.is_on"), TemplateInstantiator.reads(out))
  }

  @Test fun theHostBudgetIsEnforced() = runBlocking {
    assertIs<InstantiateResult.Failed>(noImages.instantiate(tile, Bindings.EMPTY, 100, 100, maxBytes = 100))
    Unit
  }

  @Test fun bytesThatAreNotADocumentFailCleanly() = runBlocking {
    assertIs<InstantiateResult.Failed>(noImages.instantiate("not a document".encodeUtf8(), Bindings.EMPTY, 1, 1))
    Unit
  }

  @Test fun imageUrlsResolveThroughTheSlotsValues() {
    val b = Bindings(mapOf("hero" to NamedValue.Image("https://cdn.example/a.png"), "title" to NamedValue.Str("x")))
    assertEquals("https://cdn.example/a.png", TemplateInstantiator.imageRef("livery:value/hero", b))
    assertNull(TemplateInstantiator.imageRef("livery:value/title", b), "not an image value")
    assertNull(TemplateInstantiator.imageRef("livery:value/missing", b))
    assertEquals("asset:" + "a".repeat(64), TemplateInstantiator.imageRef("asset:" + "a".repeat(64), b))
    assertNull(TemplateInstantiator.imageRef("file:///etc/passwd", b))
  }
}
