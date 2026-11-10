package ee.schimke.livery.gradle

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

class LiveryCatalogPluginTest {
  private fun project(inspect: Boolean): File = Files.createTempDirectory("app").toFile().apply {
    File(requireNotNull(System.getProperty("livery.samples")) { "run through Gradle: gradle/sample-catalogs.gradle.kts" }).copyRecursively(File(this, "livery"))
    File(this, "settings.gradle.kts").writeText("rootProject.name = \"app\"\n")
    File(this, "build.gradle.kts").writeText(
      """
      plugins { id("ee.schimke.composeai.livery.catalog") }
      livery {
        assetsPath.set("catalogs/sample")
        inspect.set($inspect)
      }
      """.trimIndent()
    )
  }

  private fun run(dir: File, vararg args: String, fail: Boolean = false) =
    GradleRunner.create().withProjectDir(dir).withPluginClasspath().withArguments(*args, "--stacktrace")
      .let { if (fail) it.buildAndFail() else it.build() }

  @Test fun buildsTheCatalogReproducibly() {
    val dir = project(inspect = false)
    val first = run(dir, "buildLiveryCatalog")
    assertEquals(TaskOutcome.SUCCESS, first.task(":buildLiveryCatalog")?.outcome)
    val out = File(dir, "build/generated/livery/assets/catalogs/sample")
    val manifest = File(out, "manifest.json").readText()
    assertTrue("\"serial\": 0" in manifest, manifest.take(300))
    assertTrue(File(out, "blobs").listFiles()!!.isNotEmpty())
    val bytes = File(out, "manifest.json").readBytes()

    File(dir, "build").deleteRecursively()
    run(dir, "buildLiveryCatalog", "--rerun-tasks")
    assertEquals(bytes.toList(), File(out, "manifest.json").readBytes().toList(), "same sources, same manifest")
    assertEquals(TaskOutcome.UP_TO_DATE, run(dir, "buildLiveryCatalog").task(":buildLiveryCatalog")?.outcome)
  }

  @Test fun aCatalogWithProblemsFailsTheBuild() {
    // The sample's other .rc files are placeholders: inspecting them fails, as it should.
    val result = run(project(inspect = true), "buildLiveryCatalog", fail = true)
    assertTrue("not a Remote Compose document" in result.output, result.output.takeLast(2000))
  }
}
