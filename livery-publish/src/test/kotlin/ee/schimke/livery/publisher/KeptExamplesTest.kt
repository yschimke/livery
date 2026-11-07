package ee.schimke.livery.publisher

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeptExamplesTest {
  private val sample = File(System.getProperty("user.dir")).resolve("../samples/catalog").canonicalFile

  private fun copy(): File = Files.createTempDirectory("kept").toFile().also { sample.copyRecursively(it) }

  private fun File.edit(path: String, f: (String) -> String) = File(this, path).let { it.writeText(f(it.readText())) }

  private val onBefore = "{ \"name\": \"Kitchen\", \"state\": \"On · 80%\", \"is_on\": true, \"level\": 0.8 }"

  @Test fun changedPreviewDataMustBeKept() {
    val tree = copy()
    tree.edit("contracts/status-tile.json") { it.replace("\"level\": 0.8", "\"level\": 0.35") }
    val r = CompatibilityCheck.compare(tree, sample, "main")
    assertFalse(r.ok)
    assertTrue(r.problems.single().startsWith("contract live/status-tile@1: example 'on' changed or was removed, and its old data isn't kept"), r.problems.toString())

    val written = KeptExamples.keep(tree, sample)
    assertEquals(1, written.size)
    assertTrue(written.single().relativeTo(tree).path.matches(Regex("contracts/examples/status-tile/on-[0-9a-f]{8}\\.json")), written.toString())
    assertTrue(CompatibilityCheck.compare(tree, sample, "main").ok, "kept now")
    assertEquals(emptyList(), KeptExamples.keep(tree, sample), "nothing more to keep")

    val kept = KeptExamples.load(tree).getValue("live/status-tile@1")
    val (label, data) = kept.entries.single()
    assertTrue(label.startsWith("on (kept "), label)
    assertEquals("0.8", data["level"].toString())
    // The catalog build ignores the kept examples: they're for checking.
    CatalogBuilder(now = { 0 }).build(tree, Layout.Static, serial = 0)
  }

  @Test fun theSameDataUnderANewNameNeedsNothingKept() {
    val tree = copy()
    tree.edit("contracts/status-tile.json") { it.replace("\"on\": {", "\"lit\": {") }
    assertEquals(emptyList(), KeptExamples.missing(tree, sample))
  }

  @Test fun theSameDataInAnotherKeyOrderIsTheSame() {
    val tree = copy()
    tree.edit("contracts/status-tile.json") { it.replace(onBefore, "{ \"level\": 0.8, \"is_on\": true, \"state\": \"On · 80%\", \"name\": \"Kitchen\" }") }
    assertEquals(emptyList(), KeptExamples.missing(tree, sample))
  }

  @Test fun keptDataCantBeDeletedLater() {
    val base = copy()
    base.edit("contracts/status-tile.json") { it.replace("\"level\": 0.8", "\"level\": 0.35") }
    KeptExamples.keep(base, sample)
    val tree = Files.createTempDirectory("next").toFile().also { base.copyRecursively(it) }
    File(tree, "contracts/examples").deleteRecursively()
    val r = CompatibilityCheck.compare(tree, base, "main")
    assertTrue(r.problems.single().startsWith("contract live/status-tile@1: example 'on' changed or was removed"), r.problems.toString())
    assertEquals(1, KeptExamples.keep(tree, base).size)
    assertTrue(CompatibilityCheck.compare(tree, base, "main").ok)
  }

  @Test fun aRemovedContractTakesItsExamplesWithIt() {
    val tree = copy()
    File(tree, "contracts/feed-article.json").delete()
    File(tree, "designs/article-card").deleteRecursively()
    assertEquals(emptyList(), KeptExamples.missing(tree, sample))
  }

  @Test fun aKeptExampleThatNoLongerBindsFails() {
    val tree = copy()
    File(tree, "contracts/examples/status-tile").mkdirs()
    File(tree, "contracts/examples/status-tile/old-00000000.json").writeText("""{ "name": "Kitchen", "state": 3 }""")
    val r = CompatibilityCheck.compare(tree, sample, "main")
    assertTrue(r.problems.any { it.startsWith("contract live/status-tile@1: kept example 'old (kept ") && "no longer binds" in it }, r.problems.toString())
  }
}
