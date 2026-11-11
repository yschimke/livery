package ee.schimke.livery.render

import ee.schimke.livery.protocol.NamedValue
import ee.schimke.livery.protocol.SizeRange
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.Target
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    assertEquals(setOf("light", "dark", "theme-dusk"), tile.map { it.mode }.toSet())
    assertEquals(9, tile.size)
    index.previews.forEach { p ->
      val image = ImageIO.read(out.resolve(p.file))
      assertEquals(p.widthDp * 2, image.width, p.file); assertEquals(p.heightDp * 2, image.height, p.file)
    }
    assertTrue(out.resolve("index.html").readText().contains("tile-default/in-app/on/light.png"))
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
    val p = index.previews.first { it.design == "tile-default" && it.template == "in-app" && it.mode == "theme-dusk" }
    // The corner is the template's own background or the pack's surface, never transparent.
    assertEquals(0xFF, ImageIO.read(out.resolve(p.file)).getRGB(0, 0) ushr 24)
  }

  /** Dark binds a dark scheme to the theme roles: the poll's card, light by its captured default, goes dark. */
  @Test fun darkBindsADarkSchemeToTheThemeRoles() {
    fun card(mode: String): Int {
      val p = index.previews.first { it.design == "poll-card" && it.example == "open" && it.mode == mode }
      val image = ImageIO.read(out.resolve(p.file))
      val rgb = image.getRGB(image.width / 2, image.height - 40)  // inside the card, below the options
      return ((rgb shr 16 and 0xFF) + (rgb shr 8 and 0xFF) + (rgb and 0xFF)) / 3
    }
    assertTrue(card("light") > 200, "light card: ${card("light")}")
    assertTrue(card("dark") < 80, "dark card: ${card("dark")}")
  }

  /** The matrix as data: every drawn preview is a case, with its document copied and its values bound. */
  @Test fun theMatrixIsExportedAsCasesAnyRendererCanPlay() {
    val drawn = index.previews.size  // renders first: the export is written by the same run
    val cases = Json.decodeFromString(PreviewCases.serializer(), out.resolve("cases.json").readText())
    assertEquals(drawn, cases.cases.size)
    val c = cases.cases.first { it.design == "tile-default" && it.example == "on" && it.mode == "dark" }
    assertTrue(out.resolve(c.document).readBytes().isNotEmpty(), c.document)
    assertEquals(NamedValue.Str("Kitchen"), c.values["name"])
    assertTrue(c.values["theme.surface"] is NamedValue.Color, "the dark scheme's roles are bound")
    assertEquals(Surfaces.IN_APP, c.target.surface)
    assertTrue(c.dark)
  }

  /** The same matrix in compose-preview's shared shape (RemoteDocumentCasesV1), for its render-cases. */
  @Test fun theMatrixIsAlsoExportedInComposePreviewsShape() {
    val drawn = index.previews.size
    val file = Json.parseToJsonElement(out.resolve(CatalogPreviews.REMOTE_DOCUMENT_CASES).readText()).jsonObject
    assertEquals("compose-preview/remote-document-cases/v1", file["schema"]!!.jsonPrimitive.content)
    val cases = file["cases"]!!.jsonArray.map { it.jsonObject }
    assertEquals(drawn, cases.size)
    val c = cases.first { it["id"]!!.jsonPrimitive.content == "tile-default/in-app/on/dark" }
    assertTrue(out.resolve(c["document"]!!.jsonPrimitive.content).isFile)
    assertEquals("androidx7", c["profile"]!!.jsonPrimitive.content)
    assertEquals(true, c["dark"]!!.jsonPrimitive.boolean)
    assertTrue(Regex("#[0-9A-F]{8}").matches(c["background"]!!.jsonPrimitive.content))
    val values = c["namedValues"]!!.jsonObject
    assertEquals("string", values["name"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
    assertEquals("Kitchen", values["name"]!!.jsonObject["value"]!!.jsonPrimitive.content)
    assertTrue(Regex("#[0-9A-F]{8}").matches(values["theme.surface"]!!.jsonObject["argb"]!!.jsonPrimitive.content))
    // Each id is unique: it names the case's image.
    assertEquals(cases.size, cases.map { it["id"]!!.jsonPrimitive.content }.toSet().size)
  }

  /** A target's profiles name the platform player; an image has no daemon type and is left out. */
  @Test fun targetsBecomePlayerProfilesAndImagesAreLeftOut() {
    assertEquals("widgetsV7", CatalogPreviews.profile(Target(Surfaces.LAUNCHER_WIDGET, 7, listOf("widgets"))))
    assertEquals("wearWidgets", CatalogPreviews.profile(Target(Surfaces.WEAR_WIDGET, 7, listOf("glance-wear"))))
    assertEquals(null, CatalogPreviews.profile(Target(Surfaces.WEB, 7, listOf("core"))))
    assertEquals(null, CatalogPreviews.remoteValue(NamedValue.Image("https://example.com/a.png")))
    assertEquals("#FF6750A4", CatalogPreviews.remoteValue(NamedValue.Color(0xFF6750A4))!!["argb"]!!.jsonPrimitive.content)
  }

  /** Exporting alone draws nothing. */
  @Test fun noDrawOnlyExports() {
    val dir = Files.createTempDirectory("cases").toFile()
    val index = CatalogPreviews.render(samples, dir, draw = false)
    assertTrue(index.previews.isEmpty())
    assertTrue(dir.resolve("cases.json").isFile)
    assertTrue(!dir.resolve("index.html").exists() && dir.walk().none { it.name.endsWith(".png") })
  }

  /** An example's label is any string: its file stays one segment inside the output, and the gallery links to it. */
  @Test fun anyLabelIsOneSafePathSegment() {
    assertEquals("on", CatalogPreviews.segment("on"))
    assertEquals("case%231", CatalogPreviews.segment("case#1"))
    assertEquals("..%2Fx", CatalogPreviews.segment("../x"))
    assertEquals("%2E%2E", CatalogPreviews.segment(".."))
    assertEquals("caf%C3%A9", CatalogPreviews.segment("café"))
    assertEquals("d/t/case%25231/light.png", CatalogPreviews.url("d/t/case%231/light.png"))
  }

  /** A header's size is pixels at the density it was generated at: a 720×400 document at 2x previews at 360×200 dp. */
  @Test fun aDocumentsSizeIsItsPixelsOverItsDensity() {
    assertEquals(360 to 200, CatalogPreviews.authoredDp(720, 400, 2f, modern = true))
    assertEquals(720 to 400, CatalogPreviews.authoredDp(720, 400, 0f, modern = true))
    assertEquals(0 to 0, CatalogPreviews.authoredDp(720, 400, 2f, modern = false))
  }

  /** An `.rc` without its sidecar is a problem, as `livery build` makes it, not a template left out. */
  @Test fun aTemplateWithoutASidecarIsAProblem() {
    val tree = Files.createTempDirectory("tree").toFile()
    samples.copyRecursively(tree)
    tree.resolve("designs/poll-card/in-app.json").delete()
    val index = CatalogPreviews.render(tree, Files.createTempDirectory("out").toFile())
    assertTrue(index.problems.any { it.design == "poll-card" && it.template == "in-app" && "no sidecar" in it.message }, index.problems.toString())
    assertTrue(index.previews.none { it.design == "poll-card" })
  }

  @Test fun aDocumentWithoutASizeGetsItsSurfacesWithinItsRange() {
    val launcher = Target(Surfaces.LAUNCHER_WIDGET, 7, size = SizeRange(minWidthDp = 250, maxWidthDp = 400))
    assertEquals(250 to 200, CatalogPreviews.size(0 to 0, launcher))
  }
}
