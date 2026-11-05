package ee.schimke.livery.publisher

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompatibilityCheckTest {
  private val sample = File(System.getProperty("user.dir")).resolve("../samples/catalog").canonicalFile

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
    assertTrue(r.warnings.any { "now requires minor 1" in it }, r.warnings.toString())
    assertTrue(r.warnings.any { "[subtitle] are optional with no contract default" in it }, r.warnings.toString())
    assertTrue("compatible" in CompatibilityCheck.text(r))
  }

  @Test fun aBreakingChangeUnderTheSameMajorFails() {
    val new = copy()
    new.edit("contracts/status-tile.json") { it.replace("\"maxLength\": 40", "\"maxLength\": 20") }
    val r = CompatibilityCheck.compare(new, sample)
    assertFalse(r.ok)
    assertTrue(r.problems.single().startsWith("contract live/status-tile@1: 0 -> 0 is not a minor change"), r.problems.toString())
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
}
