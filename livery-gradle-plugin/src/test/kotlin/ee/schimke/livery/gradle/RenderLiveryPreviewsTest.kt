package ee.schimke.livery.gradle

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

/** `renderLiveryPreviews`: a catalog's previews, from nothing but the catalog. */
class RenderLiveryPreviewsTest {
  private val samples = File(requireNotNull(System.getProperty("livery.samples")) { "run through Gradle: gradle/sample-catalogs.gradle.kts" })
  private val renderer = requireNotNull(System.getProperty("livery.previewRenderer")).split(File.pathSeparator)

  /** The sample catalog as a project's `livery/`; [placeholders] keeps the designs that aren't documents yet. */
  private fun project(placeholders: Boolean = false): File = Files.createTempDirectory("previews").toFile().apply {
    samples.copyRecursively(File(this, "livery"))
    if (!placeholders) {
      File(this, "livery/designs").walk()
        .filter { it.name.endsWith(".rc") && it.readBytes().decodeToString(endIndex = 11) == "PLACEHOLDER" }
        .toList()
        .forEach { rc -> rc.delete(); File(rc.parentFile, rc.name.removeSuffix(".rc") + ".json").delete() }
    }
    File(this, "settings.gradle.kts").writeText("""rootProject.name = "app"""" + "\nbuildCache { local { directory = file(\"build-cache\") } }\n")
    File(this, "build.gradle.kts").writeText(
      """
      plugins { id("ee.schimke.composeai.livery.catalog") }
      livery {
        inspect.set(false)
        previews { classpath.from(${renderer.joinToString { "\"" + it.replace("\\", "/") + "\"" }}) }
      }
      """.trimIndent()
    )
  }

  private fun runner(dir: File, vararg args: String) =
    GradleRunner.create().withProjectDir(dir).withPluginClasspath().withArguments(*args, "--stacktrace")

  @Test fun previewsEveryTemplateWithEveryExample() {
    val dir = project()
    val result = runner(dir, "renderLiveryPreviews").build()
    assertEquals(TaskOutcome.SUCCESS, result.task(":renderLiveryPreviews")?.outcome)
    val out = File(dir, "build/livery/previews")
    assertTrue(File(out, "tile-default/in-app/on/light.png").isFile)
    assertTrue(File(out, "poll-card/in-app/voted/dark.png").isFile)
    assertTrue("tile-default/in-app/unavailable/theme-dusk.png" in File(out, "index.html").readText())
    val index = File(out, "previews.json").readText()
    assertTrue(Regex(""""problems":\s*\[\s*]""").containsMatchIn(index), index.takeLast(300))
  }

  @Test fun upToDateThenFromTheBuildCacheAndTheConfigurationCache() {
    val dir = project()
    runner(dir, "renderLiveryPreviews", "--build-cache", "--configuration-cache").build()
    assertEquals(TaskOutcome.UP_TO_DATE, runner(dir, "renderLiveryPreviews", "--build-cache", "--configuration-cache").build().task(":renderLiveryPreviews")?.outcome)
    File(dir, "build").deleteRecursively()
    val cached = runner(dir, "renderLiveryPreviews", "--build-cache", "--configuration-cache").build()
    assertEquals(TaskOutcome.FROM_CACHE, cached.task(":renderLiveryPreviews")?.outcome)
    assertTrue("Reusing configuration cache" in cached.output)
  }

  /** `draw = false` exports the matrix for another renderer and draws nothing. */
  @Test fun exportsTheCasesWithoutDrawing() {
    val dir = project()
    File(dir, "build.gradle.kts").appendText("\nlivery { previews { draw.set(false) } }\n")
    runner(dir, "renderLiveryPreviews").build()
    val out = File(dir, "build/livery/previews")
    assertTrue(File(out, "cases.json").readText().contains("\"documents/tile-default/in-app.rc\""))
    assertTrue(File(out, "documents/tile-default/in-app.rc").isFile)
    assertTrue(out.walk().none { it.name.endsWith(".png") })
  }

  /** A template that can't be drawn fails the task, naming it, after the rest are drawn. */
  @Test fun failsOnATemplateItCantDraw() {
    val dir = project(placeholders = true)
    val result = runner(dir, "renderLiveryPreviews").buildAndFail()
    assertTrue("tile-default/wear-large: not a Remote Compose document" in result.output, result.output.takeLast(2000))
    assertTrue(File(dir, "build/livery/previews/tile-default/in-app/on/light.png").isFile)
  }
}
