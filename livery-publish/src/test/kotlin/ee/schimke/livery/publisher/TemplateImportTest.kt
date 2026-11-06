package ee.schimke.livery.publisher

import ee.schimke.livery.protocol.Target
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemplateImportTest {
  private val sample = File(System.getProperty("user.dir")).resolve("../samples/catalog").canonicalFile
  private val tile = File(sample, "designs/tile-default/in-app.rc").readBytes()
  private val inApp = Target(surface = "in-app", rcApiLevel = 7)

  private fun tree(): File = Files.createTempDirectory("import").toFile().also { sample.copyRecursively(it) }

  @Test fun writesTheDocumentAndASidecarReadFromIt() {
    val src = tree()
    val i = TemplateImport.into(src, "tile-retro", "imported", tile, inApp, mapOf("tool" to "capture"))
    assertEquals("live/status-tile@1", i.contract)
    assertEquals(listOf("is_on", "state"), i.spec.reads)
    assertEquals(listOf("toggle"), i.spec.emits)
    assertTrue(File(src, "designs/tile-retro/imported.rc").readBytes().contentEquals(tile))
    assertTrue("\"tool\": \"capture\"" in File(src, "designs/tile-retro/imported.json").readText())
    CatalogBuilder().build(src, Layout.Static)
  }

  @Test fun refusesADocumentItsContractDoesntDeclare() {
    val src = tree()
    val problems = assertFailsWith<CatalogProblems> { TemplateImport.into(src, "banner-bold", "tile", tile, inApp) }.problems
    assertEquals(listOf(
      "banner-bold/tile: reads values promo/banner@1 doesn't declare: [is_on, state]",
      "banner-bold/tile: emits actions promo/banner@1 doesn't declare: [toggle]",
    ), problems)
    assertFalse(File(src, "designs/banner-bold/tile.rc").exists())
  }

  @Test fun refusesAnUnknownDesignOrBadName() {
    val src = tree()
    assertTrue("no design nope" in assertFailsWith<CatalogProblems> { TemplateImport.into(src, "nope", "x", tile, inApp) }.problems.single())
    assertFailsWith<CatalogProblems> { TemplateImport.into(src, "tile-retro", "../x", tile, inApp) }
  }

  @Test fun undoesAnImportTheCatalogRefuses() {
    val src = tree()
    val existing = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
      .decodeFromString(TemplateSpec.serializer(), File(src, "designs/tile-retro/in-app.json").readText()).target
    // A second template for a target the design already has passes the document's checks and fails the build.
    val problems = assertFailsWith<CatalogProblems> { TemplateImport.into(src, "tile-retro", "second", tile, existing) }.problems
    assertTrue(problems.any { "two templates for one target" in it }, problems.toString())
    assertFalse(File(src, "designs/tile-retro/second.rc").exists())
    assertFalse(File(src, "designs/tile-retro/second.json").exists())
  }
}
