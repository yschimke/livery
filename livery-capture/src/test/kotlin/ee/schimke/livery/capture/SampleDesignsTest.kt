@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.livery.capture

import androidx.compose.remote.creation.compose.state.rs
import ee.schimke.livery.capture.samples.BannerClassic
import ee.schimke.livery.capture.samples.PollCard
import ee.schimke.livery.capture.samples.QuantityStepper
import ee.schimke.livery.protocol.SizeRange
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.Target
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The sample catalogs' designs captured through AndroidX's creation API. Their Kotlin is
 * livery-capture-cmp's `samples/SampleDesigns.kt`, compiled here against AndroidX's packages
 * (`generateAndroidxSamples`), with the same contracts, targets and sizes as that module's
 * `SampleCatalogs`: one template, authored through both bindings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi")
class SampleDesignsTest {
  private val catalog = File("../samples/catalog")
  private val twoWay = File("../samples/two-way")
  private val context get() = RuntimeEnvironment.getApplication()

  @Test fun capturesBannerClassic() = runBlocking {
    val contract = LiveryCapture.contract(catalog, "promo/banner@1")
    val target = Target(Surfaces.IN_APP, 7, listOf("androidx"), size = SizeRange(minWidthDp = 280, maxWidthDp = 840))
    val templates = mapOf("in-app" to LiveryCapture.capture(context, target, 360, 160, source = mapOf("design" to "banner-classic")) { BannerClassic(LiveryValues(contract)) })
    assertEquals(listOf("cta", "dismiss"), templates.getValue("in-app").emits)
    SampleCatalogs.write("catalog", "banner-classic", templates)
  }

  @Test fun capturesPollCard() = runBlocking {
    val contract = LiveryCapture.contract(catalog, "social/poll@1")
    val target = Target(Surfaces.IN_APP, 7, listOf("androidx"), size = SizeRange(minWidthDp = 280, maxWidthDp = 600))
    val templates = mapOf("in-app" to LiveryCapture.capture(context, target, 360, 280, source = mapOf("design" to "poll-card")) { PollCard(LiveryValues(contract)) })
    assertEquals(listOf("vote"), templates.getValue("in-app").emits)
    SampleCatalogs.write("catalog", "poll-card", templates)
  }

  @Test fun capturesQuantityStepper() = runBlocking {
    val contract = LiveryCapture.contract(twoWay, "shop/quantity@1")
    val target = Target(Surfaces.IN_APP, 7, listOf("androidx"), size = SizeRange(minWidthDp = 240, maxWidthDp = 600))
    val templates = mapOf("in-app" to LiveryCapture.capture(context, target, 360, 80, source = mapOf("design" to "quantity-stepper")) { QuantityStepper(LiveryValues(contract)) })
    assertEquals(listOf("setQuantity"), templates.getValue("in-app").emits)
    SampleCatalogs.write("two-way", "quantity-stepper", templates)
  }

  @Test fun setChecksTheActionAndTheInputsType() {
    val v = LiveryValues(LiveryCapture.contract(twoWay, "shop/quantity@1"))
    assertEquals(
      "shop/quantity@1 action 'setQuantity' sets quantity, a int, not string",
      assertFailsWith<IllegalArgumentException> { v.set("setQuantity", "3".rs) }.message,
    )
  }
}
