package ee.schimke.livery.review

import ee.schimke.livery.publisher.CompatibilityCheck
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewPreviewsTest {
  private val sample = File(System.getProperty("user.dir")).resolve("../samples/catalog").canonicalFile
  private fun temp(name: String) = Files.createTempDirectory(name).toFile()
  private val keep = System.getProperty("livery.render.out")?.let(::File)

  @Test fun aChangedDesignIsDrawnBeforeAndAfterWithEachExample() {
    val tree = temp("tree").also { sample.copyRecursively(it) }
    // tile-default's in-app document is replaced by another build of it (the sample's other
    // templates are placeholders, which aren't drawn).
    javaClass.getResourceAsStream("/demo-tile.rc")!!.use { File(tree, "designs/tile-default/in-app.rc").writeBytes(it.readBytes()) }
    val out = keep?.resolve("changed") ?: temp("previews")
    val c = CompatibilityCheck.compareTrees(tree, sample, "main")
    val previews = ReviewPreviews(out, imageBase = "https://example.test/").render(c)

    val tile = previews.single()
    assertEquals("tile-default", tile.design)
    assertNull(tile.failure)
    assertEquals(listOf("on", "off", "unavailable"), tile.shots.map { it.example })
    for (shot in tile.shots) {
      val after = shot.after!!.removePrefix("https://example.test/")
      val before = shot.before!!.removePrefix("https://example.test/")
      assertTrue(after.startsWith("tile-default.${shot.example}.") && after.endsWith(".after.png"), after)
      assertEquals(after.removeSuffix(".after.png") + ".before.png", before)
      val (a, b) = listOf(after, before).map { File(out, it).readBytes() }
      assertTrue(a.isNotEmpty() && b.isNotEmpty())
      assertEquals(a.contentEquals(b), shot.unchanged)
    }

    val md = CompatibilityCheck.markdown(c.report, previews)
    assertTrue("**Previews**" in md, md)
    assertTrue(("| before |" in md && "| after |" in md) || "| unchanged |" in md, md)
    assertTrue("<img src=\"https://example.test/tile-default.on." in md, md)
  }

  @Test fun aNewCatalogIsDrawnAfterOnly() {
    val out = keep?.resolve("added") ?: temp("previews")
    val c = CompatibilityCheck.compareTrees(sample, temp("empty"), "nothing")
    val previews = ReviewPreviews(out).render(c)
    assertEquals(c.report.templates.map { it.design }.distinct().sorted(), previews.map { it.design }.sorted())
    // tile-default and banner-classic are real documents; the other designs' are placeholders.
    val real = setOf("tile-default", "banner-classic")
    for (design in real) {
      val p = previews.single { it.design == design }
      assertNull(p.failure)
      assertTrue(p.shots.isNotEmpty() && p.shots.all { it.before == null && it.after != null }, p.toString())
    }
    // The placeholders are said, not drawn.
    assertTrue(previews.filter { it.design !in real }.all { it.failure == "no template is a Remote Compose document" }, previews.toString())
  }

  @Test fun namesNeverCollide() {
    val c = CompatibilityCheck.compareTrees(sample, temp("empty"), "nothing")
    val files = ReviewPreviews(temp("previews")).render(c).flatMap { p -> p.shots.mapNotNull { it.after } }
    assertEquals(files.size, files.toSet().size)
  }

  @Test fun aRemovedSizeIsDrawnFromTheOldTree() {
    // Only the old tree has a template covering the size asked for: its before is still drawn.
    val tree = temp("tree").also { sample.copyRecursively(it) }
    val old = temp("old").also { sample.copyRecursively(it) }
    File(old, "designs/tile-default/in-app.json").let { f ->
      f.writeText(f.readText().replace("\"profiles\": [\"androidx\"]", "\"profiles\": [\"androidx\"], \"size\": { \"minWidthDp\": 500 }"))
    }
    File(tree, "designs/tile-default/in-app.json").let { f ->
      f.writeText(f.readText().replace("\"profiles\": [\"androidx\"]", "\"profiles\": [\"androidx\"], \"size\": { \"maxWidthDp\": 499 }"))
    }
    val c = CompatibilityCheck.compareTrees(tree, old, "main")
    val tile = ReviewPreviews(temp("previews"), size = 600 to 200).render(c).single { it.design == "tile-default" }
    assertNull(tile.failure, tile.toString())
    assertTrue(tile.shots.all { it.before != null && it.after == null }, tile.toString())
  }

  @Test fun aTemplateWithOnlyAMaximumIsDrawnWithinIt() {
    val tree = temp("tree").also { sample.copyRecursively(it) }
    File(tree, "designs/tile-default/in-app.json").let { f ->
      f.writeText(f.readText().replace("\"profiles\": [\"androidx\"]", "\"profiles\": [\"androidx\"], \"size\": { \"maxWidthDp\": 180, \"maxHeightDp\": 90 }"))
    }
    val tile = ReviewPreviews(temp("previews")).render(CompatibilityCheck.compareTrees(tree, sample, "main")).single { it.design == "tile-default" }
    assertEquals(180 to 90, tile.widthDp to tile.heightDp)
  }

  @Test fun eachSideBindsWithItsOwnContract() {
    // The template is unchanged, a contract default isn't: the before is drawn with the old one.
    val tree = temp("tree").also { sample.copyRecursively(it) }
    File(tree, "designs/tile-default/launcher.rc").writeText("changed placeholder")
    File(tree, "contracts/status-tile.json").let { f ->
      f.writeText(f.readText().replace("\"available\": { \"type\": \"bool\", \"default\": true }", "\"available\": { \"type\": \"bool\", \"default\": false }"))
    }
    val c = CompatibilityCheck.compareTrees(tree, sample, "main")
    val tile = ReviewPreviews(temp("previews")).render(c).single { it.design == "tile-default" }
    assertNull(tile.failure, tile.toString())
    assertTrue(tile.shots.all { it.before!!.endsWith(".before.png") }, "drawn on its own, not reused: $tile")
  }

  @Test fun theSameTemplateAndContractDrawOnce() {
    val tree = temp("tree").also { sample.copyRecursively(it) }
    File(tree, "designs/tile-default/launcher.rc").writeText("changed placeholder")
    val tile = ReviewPreviews(temp("previews")).render(CompatibilityCheck.compareTrees(tree, sample, "main")).single { it.design == "tile-default" }
    assertTrue(tile.shots.all { it.before == it.after && it.unchanged }, tile.toString())
  }

  @Test fun aTemplateThatDropsAFontIsDrawnOnBothSides() {
    // The document and contract are the same; the old template also had a bundled font.
    val tree = temp("tree").also { sample.copyRecursively(it) }
    File(tree, "designs/tile-default/launcher.rc").writeText("changed placeholder")
    val old = temp("old").also { sample.copyRecursively(it) }
    File(old, "designs/tile-default/in-app.json").let { f ->
      f.writeText(f.readText().replace("\"emits\": [", "\"fonts\": [\"Placeholder Sans\"], \"emits\": ["))
    }
    val c = CompatibilityCheck.compareTrees(tree, old, "main")
    val tile = ReviewPreviews(temp("previews")).render(c).single { it.design == "tile-default" }
    assertNull(tile.failure, tile.toString())
    assertTrue(tile.shots.all { it.before!!.endsWith(".before.png") }, "not reused: $tile")
  }

  @Test fun aContractChangeAloneIsDrawn() {
    // No template changed; a default did, so tile-default draws differently.
    val tree = temp("tree").also { sample.copyRecursively(it) }
    File(tree, "contracts/status-tile.json").let { f ->
      f.writeText(f.readText().replace("\"available\": { \"type\": \"bool\", \"default\": true }", "\"available\": { \"type\": \"bool\", \"default\": false }"))
    }
    val c = CompatibilityCheck.compareTrees(tree, sample, "main")
    assertTrue(c.report.templates.all { it.status == "unchanged" }, c.report.templates.toString())
    val tile = ReviewPreviews(temp("previews")).render(c).single { it.design == "tile-default" }
    assertTrue(tile.shots.all { it.before!!.endsWith(".before.png") }, tile.toString())
  }

  @Test fun keptPreviewDataIsDrawnToo() {
    val tree = temp("tree").also { sample.copyRecursively(it) }
    File(tree, "designs/tile-default/launcher.rc").writeText("changed placeholder")
    File(tree, "contracts/examples/status-tile").mkdirs()
    File(tree, "contracts/examples/status-tile/dim-1a2b3c4d.json").writeText("""{ "name": "Hall", "state": "Dim", "is_on": true, "level": 0.05 }""")
    val tile = ReviewPreviews(temp("previews")).render(CompatibilityCheck.compareTrees(tree, sample, "main")).single { it.design == "tile-default" }
    assertEquals(listOf("on", "off", "unavailable"), tile.shots.take(3).map { it.example })
    assertTrue(tile.shots.single { it.example.startsWith("dim (kept ") }.after != null, tile.toString())
    assertNull(tile.failure)
  }

  @Test fun anUnchangedTreeDrawsNothing() {
    val copy = temp("copy").also { sample.copyRecursively(it) }
    assertEquals(emptyList(), ReviewPreviews(temp("previews")).render(CompatibilityCheck.compareTrees(sample, copy)))
  }
}
