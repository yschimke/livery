package ee.schimke.livery.publisher

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompatibilityCheckTest {
  private val sample = File(requireNotNull(System.getProperty("livery.samples")) { "run through Gradle: gradle/sample-catalogs.gradle.kts" }).canonicalFile

  private fun copy(): File = Files.createTempDirectory("check").toFile().also { sample.copyRecursively(it) }

  private fun File.edit(path: String, f: (String) -> String) = File(this, path).let { it.writeText(f(it.readText())) }

  @Test fun theSameTreeIsCompatible() {
    val r = CompatibilityCheck.compare(sample, copy())
    assertTrue(r.ok, r.problems.toString())
    assertTrue(r.templates.all { it.status == "unchanged" }, r.templates.toString())
    assertTrue(r.contracts.all { it.changes.isEmpty() }, r.contracts.toString())
  }

  @Test fun aCompatibleMinorAndATemplateThatUsesIt() {
    val new = copy()
    new.edit("contracts/status-tile.json") {
      it.replace("\"major\": 1,", "\"major\": 1, \"minor\": 1,")
        .replace("\"available\": { \"type\": \"bool\", \"default\": true },", "\"available\": { \"type\": \"bool\", \"default\": true },\n    \"subtitle\": { \"type\": \"string\", \"since\": 1 },")
    }
    new.edit("designs/tile-default/launcher.json") { it.replace("\"reads\": [\"name\",\"state\",\"is_on\",\"available\"]", "\"reads\": [\"name\",\"state\",\"is_on\",\"available\",\"subtitle\"]") }
    val r = CompatibilityCheck.compare(new, sample)
    assertTrue(r.ok, r.problems.toString())
    val tile = r.contracts.single { it.contract == "live/status-tile@1" }
    assertEquals(0, tile.fromMinor); assertEquals(1, tile.toMinor); assertFalse(tile.breaking)
    val launcher = r.templates.single { it.design == "tile-default" && it.target.startsWith("launcher-widget") }
    assertEquals(listOf("subtitle"), launcher.readsAdded)
    assertEquals(1, launcher.requiresMinor)
    assertEquals(mapOf("available" to "default", "is_on" to "default", "name" to "required", "state" to "required", "subtitle" to "document"), launcher.values)
    assertTrue(r.warnings.any { "now needs contract minor 1" in it }, r.warnings.toString())
    assertTrue(r.warnings.any { "`subtitle` is optional and its contract gives it no default" in it }, r.warnings.toString())
    assertTrue("compatible" in CompatibilityCheck.text(r))

    // The comment sums templates up per design and says each note once, with the designs it's in.
    val md = CompatibilityCheck.markdown(r)
    assertTrue("| `tile-default` | 1 changed | " in md, md)
    assertTrue("- `subtitle` is optional and its contract gives it no default: when an app leaves it out, the template draws its own built-in fallback. In 1 template: `tile-default`" in md, md)
    assertTrue("<details><summary>Every changed template (1)</summary>" in md, md)
    assertFalse("tile-default launcher-widget" in md.substringBefore("<details>"), "no per-template warnings above the fold: $md")
  }

  @Test fun aCommentNamesAShortRevision() {
    val r = CompatibilityCheck.compare(sample, copy(), against = "a489de5cf731f0b53e20591bf3375a60bfd92bb2")
    assertTrue(CompatibilityCheck.markdown(r).startsWith("### Compatible with `a489de5`: nothing breaks for existing apps"))
  }

  @Test fun aBreakingChangeUnderTheSameMajorFails() {
    val new = copy()
    new.edit("contracts/status-tile.json") { it.replace("\"maxLength\": 40", "\"maxLength\": 20") }
    val r = CompatibilityCheck.compare(new, sample)
    assertFalse(r.ok)
    assertTrue(r.problems.single().startsWith("contract live/status-tile@1 changed in a way apps built for the old one can't handle"), r.problems.toString())
    assertTrue("NOT compatible" in CompatibilityCheck.text(r))
  }

  @Test fun aTreeThatDoesntBuildFails() {
    val new = copy()
    new.edit("designs/tile-default/launcher.json") { it.replace("\"emits\": [\"toggle\"]", "\"emits\": [\"explode\"]") }
    val r = CompatibilityCheck.compare(new, sample)
    assertFalse(r.ok)
    assertTrue(r.problems.any { "explode" in it }, r.problems.toString())
  }

  @Test fun againstAGitRevision() {
    val repo = Files.createTempDirectory("repo").toFile()
    fun git(vararg a: String) {
      val p = ProcessBuilder(listOf("git", "-C", repo.path, "-c", "user.name=t", "-c", "user.email=t@example.com") + a).redirectErrorStream(true).start()
      val out = p.inputStream.readBytes().decodeToString()
      check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { out }
    }
    git("init", "-q")
    val tree = File(repo, "catalog").also { sample.copyRecursively(it) }
    git("add", "."); git("commit", "-q", "-m", "catalog")
    tree.edit("designs/tile-default/launcher.json") { it.replace("\"reads\": [\"name\",\"state\",\"is_on\",\"available\"]", "\"reads\": [\"name\",\"state\"]") }
    val r = CompatibilityCheck.check(tree, "HEAD")
    assertTrue(r.ok, r.problems.toString())
    assertEquals("HEAD", r.against)
    assertEquals(listOf("available", "is_on"), r.templates.single { it.design == "tile-default" && it.target.startsWith("launcher-widget") }.readsRemoved)

    // A tree that didn't exist at the revision: everything is new.
    val fresh = File(repo, "other").also { sample.copyRecursively(it) }
    val r2 = CompatibilityCheck.check(fresh, "HEAD")
    assertTrue(r2.ok, r2.problems.toString())
    assertTrue(r2.templates.all { it.status == "added" })
  }

  @Test fun targetsThatDifferOnlyInProfilesAreReportedApart() {
    fun File.addProfileTwin(reads: String) {
      File(this, "designs/tile-default/in-app.rc").copyTo(File(this, "designs/tile-default/in-app-compose.rc"), overwrite = true)
      File(this, "designs/tile-default/in-app-compose.json").writeText("""{ "target": { "surface": "in-app", "rcApiLevel": 7, "profiles": ["compose"] }, "reads": [$reads] }""")
    }
    val old = copy().apply { addProfileTwin("\"name\",\"state\"") }
    val new = copy().apply { addProfileTwin("\"name\"") }
    val r = CompatibilityCheck.compare(new, old)
    val inApp = r.templates.filter { it.design == "tile-default" && it.target.startsWith("in-app") }
    assertEquals(2, inApp.size, inApp.toString())
    assertEquals(listOf("state"), inApp.single { "compose" in it.target }.readsRemoved)
    assertEquals("unchanged", inApp.single { "androidx" in it.target }.status)
  }
}
