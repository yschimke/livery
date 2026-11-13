package ee.schimke.livery.publisher

import ee.schimke.livery.publisher.CompatibilityCheck.ContractReport
import ee.schimke.livery.publisher.CompatibilityCheck.Report
import ee.schimke.livery.publisher.CompatibilityCheck.TemplateReport
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pull request comment: what a reviewer reads in ten seconds. */
class CompatibilityCommentTest {
  private val sample = File(requireNotNull(System.getProperty("livery.samples")) { "run through Gradle: gradle/sample-catalogs.gradle.kts" }).canonicalFile
  private fun copy(): File = Files.createTempDirectory("comment").toFile().also { sample.copyRecursively(it) }
  private fun File.edit(path: String, f: (String) -> String) = File(this, path).let { it.writeText(f(it.readText())) }

  private val hash = "f192e328649d4c01337aa02792c3d2715b61dbb6"
  private val optional = "`extra` is optional and its contract gives it no default: when an app leaves it out, the template draws its own built-in fallback"

  private fun template(design: String, width: String, status: String, bytes: Long, was: Long?, notes: List<String> = emptyList()) = TemplateReport(
    design, "launcher-widget [widgets] $width-maxx190-maxdp L7", status, bytes = bytes, previousBytes = was, notes = notes,
  )

  /** The visible part: what is not inside a `<details>`. */
  private fun visible(md: String) = md.substringBefore("<details>").trimEnd().lines()

  @Test fun noChangesSaysSoInOneLine() {
    val md = CompatibilityCheck.markdown(CompatibilityCheck.compare(sample, copy(), against = hash))
    assertEquals(
      """
      ### Compatible with `f192e32`: nothing breaks for existing apps

      3 designs, 4 templates: none changed
      """.trimIndent(),
      md.trim().replace(Regex("\\d+ designs?, \\d+ templates?"), "3 designs, 4 templates"),
    )
  }

  @Test fun aRepeatedNoteOverManyTemplatesIsOneLinePerMessage() {
    val designs = listOf("headphone-christmas", "headphone-halloween", "headphone-new-year", "headphone-winter", "headphone-spring", "headphone-summer")
    val templates = designs.flatMap { d ->
      (0 until 9).map { template(d, "${40 + it * 10}", "changed", 30_000L + it, 34_000L + it, notes = listOf(optional)) } +
        (0 until 4).map { template(d, "${500 + it}", "unchanged", 1_000, 1_000) }
    }
    val md = CompatibilityCheck.markdown(Report(hash, emptyList(), templates, problems = emptyList(), warnings = templates.flatMap { t -> t.notes.map { "${t.design} ${t.target}: $it" } }))
    assertEquals(
      """
      ### Compatible with `f192e32`: nothing breaks for existing apps

      6 designs, 78 templates: 54 changed, 24 unchanged

      **Worth a look**
      - $optional. In 54 templates: `headphone-christmas` (9), `headphone-halloween` (9), `headphone-new-year` (9), `headphone-winter` (9) and 2 more designs

      <details><summary>Changed designs (6)</summary>
      """.trimIndent().substringBefore("<details>").trimEnd(),
      visible(md).take(6).joinToString("\n"),
    )
    assertTrue(visible(md).size < 25, md)
    // The note appears once above the fold, never once per template.
    assertEquals(1, md.substringBefore("<details>").lines().count { "built-in fallback" in it }, md)
    assertFalse("L7" in md.substringBefore("<details>"), "target jargon stays in the collapsed list: $md")
    assertTrue("| `headphone-christmas` | 9 changed | 30.0–30.0 KB (was 34.0–34.0 KB, −12%) |" in md || "| `headphone-christmas` | 9 changed | " in md, md)
    assertTrue("_Templates = one per screen size or surface" in md, md)
  }

  @Test fun aBreakingContractChangeSaysWhatToDo() {
    val new = copy()
    new.edit("contracts/status-tile.json") { it.replace("\"maxLength\": 40", "\"maxLength\": 20") }
    val r = CompatibilityCheck.compare(new, sample, against = hash)
    val md = CompatibilityCheck.markdown(r)
    assertEquals("### Not compatible with `f192e32`: 1 problem to fix", md.lines().first())
    val fix = md.lines().dropWhile { it != "**Fix before merging**" }.drop(1).first()
    assertTrue(fix.startsWith("- contract live/status-tile@1 changed in a way apps built for the old one can't handle: "), fix)
    assertTrue(fix.endsWith("Make it an additive change (and raise the contract's minor), or start a new major"), fix)
    assertTrue("breaking:" !in fix, fix)
    assertTrue("**breaking**" in md, md)
    assertTrue(visible(md).size < 25, md)
    assertTrue("NOT compatible: 1 problem to fix" in CompatibilityCheck.text(r), CompatibilityCheck.text(r))
  }

  @Test fun problemsComeFirstAndAreCapped() {
    val problems = (1..9).map { "problem $it" }
    val md = CompatibilityCheck.markdown(Report(hash, emptyList(), emptyList(), problems = problems, warnings = emptyList()))
    assertEquals(
      """
      ### Not compatible with `f192e32`: 9 problems to fix

      **Fix before merging**
      - problem 1
      - problem 2
      - problem 3
      - problem 4
      - problem 5
      - problem 6
      <details><summary>3 more problems</summary>
      """.trimIndent(),
      md.lines().take(10).joinToString("\n"),
    )
  }

  @Test fun anOptionalValueTheBaseAlreadyLackedADefaultForIsNotReportedAgain() {
    fun File.withSubtitle() {
      edit("contracts/status-tile.json") {
        it.replace("\"major\": 1,", "\"major\": 1, \"minor\": 1,")
          .replace("\"available\": { \"type\": \"bool\", \"default\": true },", "\"available\": { \"type\": \"bool\", \"default\": true },\n    \"subtitle\": { \"type\": \"string\", \"since\": 1 },")
      }
      edit("designs/tile-default/launcher.json") { it.replace("\"reads\": [\"name\",\"state\",\"is_on\",\"available\"]", "\"reads\": [\"name\",\"state\",\"is_on\",\"available\",\"subtitle\"]") }
    }
    val base = copy().apply { withSubtitle() }
    // The template changes (it stops reading `available`), but `subtitle` was just as optional before.
    val new = copy().apply { withSubtitle(); edit("designs/tile-default/launcher.json") { it.replace("\"is_on\",\"available\",\"subtitle\"", "\"is_on\",\"subtitle\"") } }
    val r = CompatibilityCheck.compare(new, base, against = hash)
    assertEquals("changed", r.templates.single { it.design == "tile-default" && it.target.startsWith("launcher-widget") }.status)
    assertTrue(r.warnings.none { "subtitle" in it }, r.warnings.toString())
    assertFalse("Worth a look" in CompatibilityCheck.markdown(r))
  }

  @Test fun removedTemplatesAreOneLinePerMessageNotOnePerTemplate() {
    fun File.addTwin(profile: String, reads: String) {
      File(this, "designs/tile-default/in-app.rc").copyTo(File(this, "designs/tile-default/in-app-$profile.rc"), overwrite = true)
      File(this, "designs/tile-default/in-app-$profile.json").writeText("""{ "target": { "surface": "in-app", "rcApiLevel": 7, "profiles": ["$profile"] }, "reads": [$reads] }""")
    }
    val base = copy().apply { addTwin("compose", "\"name\""); addTwin("compact", "\"name\"") }
    val r = CompatibilityCheck.compare(copy(), base, against = hash)
    assertEquals(2, r.templates.count { it.status == "removed" }, r.templates.toString())
    val md = CompatibilityCheck.markdown(r)
    assertEquals(1, md.lines().count { "removed: apps get another design for this size or surface" in it }, md)
    assertTrue("In 2 templates: `tile-default`" in md, md)
    assertFalse(md.lines().any { it.startsWith("- tile-default in-app") }, "no raw per-template warning: $md")
  }

  @Test fun theComparedRevisionIsShortenedWhenItIsAHash() {
    val md = CompatibilityCheck.markdown(Report("a489de5cf731f0b53e20591bf3375a60bfd92bb2", listOf(ContractReport("x@1", 0, 0, emptyList(), false)), emptyList(), problems = emptyList(), warnings = emptyList()))
    assertEquals("### Compatible with `a489de5`: nothing breaks for existing apps", md.lines().first())
    assertEquals("### Compatible with `main`: nothing breaks for existing apps", CompatibilityCheck.markdown(Report("main", emptyList(), emptyList(), problems = emptyList(), warnings = emptyList())).lines().first())
  }
}
