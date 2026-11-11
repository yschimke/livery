package ee.schimke.livery.capture.cmp

import ee.schimke.livery.capture.CapturedTemplate
import ee.schimke.livery.capture.CatalogContracts
import ee.schimke.livery.capture.DesignFolder
import ee.schimke.livery.capture.cmp.samples.BannerClassic
import ee.schimke.livery.capture.cmp.samples.PollCard
import ee.schimke.livery.capture.cmp.samples.QuantityStepper
import ee.schimke.livery.protocol.SizeRange
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.Target
import java.io.File

/**
 * The sample catalogs' Compose-authored designs (`samples/SampleDesigns.kt`), each with its
 * catalog, contract, target and capture size, and where they go. The repository commits their
 * Kotlin, never their `.rc`: `:livery-capture-cmp:captureSamples` runs the `Sample*Test`s with
 * `livery.capture.out` set, and `:livery-capture-cmp:sampleCatalogs` lays the captures over
 * `samples/<catalog>/` in `livery-capture-cmp/build/samples/`, which every test, the CLI checks and
 * CI read. In the ordinary tests the property is unset, and a sample is captured and checked but
 * not written.
 */
internal object SampleCatalogs {
  /** The hand-written sample source trees (`samples/`), from this module's directory. */
  val source = File("../samples")

  suspend fun bannerClassic(): CapturedTemplate {
    val contract = CatalogContracts.contract(File(source, "catalog"), "promo/banner@1")
    val target = Target(Surfaces.IN_APP, 7, listOf("androidx"), size = SizeRange(minWidthDp = 280, maxWidthDp = 840))
    return LiveryCapture.capture(target, 360, 160, source = mapOf("design" to "banner-classic")) { BannerClassic(LiveryValues(contract)) }
  }

  suspend fun pollCard(): CapturedTemplate {
    val contract = CatalogContracts.contract(File(source, "catalog"), "social/poll@1")
    val target = Target(Surfaces.IN_APP, 7, listOf("androidx"), size = SizeRange(minWidthDp = 280, maxWidthDp = 600))
    return LiveryCapture.capture(target, 360, 280, source = mapOf("design" to "poll-card")) { PollCard(LiveryValues(contract)) }
  }

  suspend fun quantityStepper(): CapturedTemplate {
    val contract = CatalogContracts.contract(File(source, "two-way"), "shop/quantity@1")
    val target = Target(Surfaces.IN_APP, 7, listOf("androidx"), size = SizeRange(minWidthDp = 240, maxWidthDp = 600))
    return LiveryCapture.capture(target, 360, 80, source = mapOf("design" to "quantity-stepper")) { QuantityStepper(LiveryValues(contract)) }
  }

  fun write(catalog: String, design: String, templates: Map<String, CapturedTemplate>) {
    val out = System.getProperty("livery.capture.out") ?: return
    DesignFolder(File(out, "$catalog/designs/$design")).write(templates)
  }
}
