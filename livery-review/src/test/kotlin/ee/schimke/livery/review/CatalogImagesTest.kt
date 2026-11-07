package ee.schimke.livery.review

import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.livery.client.Bindings
import ee.schimke.livery.protocol.NamedValue
import ee.schimke.livery.publisher.CompatibilityCheck
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class CatalogImagesTest {
  private val sample = File(System.getProperty("user.dir")).resolve("../samples/catalog").canonicalFile
  private val catalog = CompatibilityCheck.compareTrees(sample, Files.createTempDirectory("empty").toFile()).after!!
  // banner-bold ships assets/hero.png.
  private val hero = catalog.manifest.assets.first { it.kind == ee.schimke.livery.protocol.AssetKind.Image }.sha256

  private fun url(u: String) = RcBitmapData(imageId = 1, width = 10, height = 10, type = 0, encoding = RcBitmapData.ENCODING_URL, data = u.encodeToByteArray())

  @Test fun anAssetIsDrawnFromTheCatalog() = runBlocking {
    val image = assertNotNull(CatalogImages(catalog, Bindings(emptyMap())).load(url("asset:$hero")))
    assertEquals(true, image.width > 0)
  }

  @Test fun anImageValueGoesThroughTheExample() = runBlocking {
    val bindings = Bindings(mapOf("hero" to NamedValue.Image("asset:$hero")))
    assertNotNull(CatalogImages(catalog, bindings).load(url("livery:value/hero")))
    assertNull(CatalogImages(catalog, Bindings(emptyMap())).load(url("livery:value/hero")), "no value, no image")
  }

  @Test fun aTemplatesFontsComeFromTheCatalog() {
    val banner = catalog.manifest.templates.single { it.designId == "banner-bold" }
    val fonts = CatalogFonts.forTemplate(catalog, banner)
    kotlin.test.assertTrue("Placeholder Sans" in fonts.families, fonts.families.toString())
    val tile = catalog.manifest.templates.first { it.designId == "tile-default" }
    kotlin.test.assertTrue("Placeholder Sans" !in CatalogFonts.forTemplate(catalog, tile).families)
  }

  @Test fun anAssetTheCatalogLacksDrawsNothing() = runBlocking {
    assertNull(CatalogImages(catalog, Bindings(emptyMap())).load(url("asset:" + "0".repeat(64))))
  }
}
