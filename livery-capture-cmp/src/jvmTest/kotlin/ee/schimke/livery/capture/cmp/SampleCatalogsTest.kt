package ee.schimke.livery.capture.cmp

import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.livery.capture.CatalogContracts
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

/**
 * Captures the sample catalogs' Compose-authored designs on the plain JVM, and, under
 * `./gradlew :livery-capture-cmp:sampleCatalogs`, writes them into the built sample catalogs.
 */
class SampleCatalogsTest {
  @Test fun capturesBannerClassic() = runBlocking {
    val templates = mapOf("in-app" to SampleCatalogs.bannerClassic())
    assertEquals(listOf("cta", "dismiss"), templates.getValue("in-app").emits)
    SampleCatalogs.write("catalog", "banner-classic", templates)
  }

  @Test fun capturesPollCard() = runBlocking {
    val templates = mapOf("in-app" to SampleCatalogs.pollCard())
    assertEquals(listOf("vote"), templates.getValue("in-app").emits)
    SampleCatalogs.write("catalog", "poll-card", templates)
  }

  @Test fun capturesQuantityStepper() = runBlocking {
    val templates = mapOf("in-app" to SampleCatalogs.quantityStepper())
    assertEquals(listOf("setQuantity"), templates.getValue("in-app").emits)
    SampleCatalogs.write("two-way", "quantity-stepper", templates)
  }

  @Test fun setChecksTheActionAndTheInputsType() {
    val v = LiveryValues(CatalogContracts.contract(File(SampleCatalogs.source, "two-way"), "shop/quantity@1"))
    assertEquals(
      "shop/quantity@1 action 'setQuantity' sets quantity, a int, not string",
      assertFailsWith<IllegalArgumentException> { v.set("setQuantity", "3".rs) }.message,
    )
  }
}
