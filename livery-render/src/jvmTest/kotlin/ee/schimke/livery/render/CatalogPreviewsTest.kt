package ee.schimke.livery.render

import ee.schimke.livery.protocol.SizeRange
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.Target
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every template in the sample catalog, previewed with nothing but the tree itself. */
class CatalogPreviewsTest {
  private val samples = File(requireNotNull(System.getProperty("livery.samples")) { "run through Gradle: gradle/sample-catalogs.gradle.kts" })
  private val out: File = System.getProperty("livery.render.out")?.let { File(it, "catalog-previews") }
    ?: Files.createTempDirectory("previews").toFile()

  private val index by lazy { CatalogPreviews.render(samples, out) }

  @Test fun everyTemplateWithEveryExampleInEveryMode() {
    // The Kotlin-authored designs, each with every example of its contract.
    assertEquals(setOf("banner-classic", "poll-card", "tile-default"), index.previews.map { it.design }.toSet())
    val tile = index.previews.filter { it.design == "tile-default" }
    assertEquals(setOf("on", "off", "unavailable"), tile.map { it.example }.toSet())
    // A host-themed template: light, dark and the tree's theme pack.
    assertEquals(setOf("light", "dark", "dusk"), tile.map { it.mode }.toSet())
    assertEquals(9, tile.size)
    index.previews.forEach { p ->
      val image = ImageIO.read(out.resolve(p.file))
      assertEquals(p.widthDp * 2, image.width, p.file); assertEquals(p.heightDp * 2, image.height, p.file)
    }
    assertTrue(out.resolve("index.html").readText().contains("tile-default/in-app-on-light.png"))
    assertTrue(out.resolve("previews.json").isFile)
  }

  /** The sample designs with no Kotlin source yet are placeholders, not documents: each is a problem, and the rest still render. */
  @Test fun aTemplateThePlayerCantReadIsAProblemNotAFailure() {
    val unreadable = index.problems.filter { it.message.startsWith("not a Remote Compose document") }
    assertTrue(unreadable.any { it.design == "tile-default" && it.template == "wear-large" }, index.problems.toString())
    assertEquals(unreadable, index.problems, "only the placeholders")
  }

  /** A watch template without a size of its own gets its container's. */
  @Test fun aWatchTemplateWithoutASizeGetsItsContainers() {
    assertEquals(192 to 192, CatalogPreviews.size(0 to 0, Target(Surfaces.WEAR_WIDGET, 7, theme = "baked:dark", container = "wear:large")))
  }

  @Test fun aTemplateDrawsOverItsThemesSurface() {
    val p = index.previews.first { it.design == "tile-default" && it.template == "in-app" && it.mode == "dusk" }
    // The corner is the template's own background or the pack's surface, never transparent.
    assertEquals(0xFF, ImageIO.read(out.resolve(p.file)).getRGB(0, 0) ushr 24)
  }

  @Test fun aDocumentWithoutASizeGetsItsSurfacesWithinItsRange() {
    val launcher = Target(Surfaces.LAUNCHER_WIDGET, 7, size = SizeRange(minWidthDp = 250, maxWidthDp = 400))
    assertEquals(250 to 200, CatalogPreviews.size(0 to 0, launcher))
  }
}
