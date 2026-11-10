package ee.schimke.livery.gradle

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

/**
 * `livery { capture { … } }`: the catalog's templates come from the project's own code at build
 * time. Here the "capture" is a plain Java `main` that lays down a known catalog, so the test
 * checks the wiring (arguments, outputs, up-to-date checks, the build cache, the configuration
 * cache) without Compose.
 */
class CaptureLiveryCatalogTest {
  private val samples = File(requireNotNull(System.getProperty("livery.samples")) { "run through Gradle: gradle/sample-catalogs.gradle.kts" })

  private fun project(): File = Files.createTempDirectory("capture").toFile().apply {
    // The hand-written part: everything but the designs, which the capture writes.
    samples.copyRecursively(File(this, "catalog"))
    File(this, "catalog/designs").deleteRecursively()
    File(this, "settings.gradle.kts").writeText(
      """
      rootProject.name = "app"
      buildCache { local { directory = file("build-cache") } }
      """.trimIndent()
    )
    File(this, "build.gradle.kts").writeText(
      """
      plugins {
        java
        id("ee.schimke.composeai.livery.catalog")
      }
      livery {
        source.set(layout.projectDirectory.dir("catalog"))
        inspect.set(false)
        capture {
          mainClass.set("Capture")
          jvmArgs.add("-Dlivery.designs=${samples.resolve("designs").absolutePath.replace("\\", "/")}")
        }
      }
      """.trimIndent()
    )
    capture(this, marker = "first")
  }

  /** A capture entry point: copies the hand-written tree and the designs into `<out>`. */
  private fun capture(dir: File, marker: String) {
    File(dir, "src/main/java").mkdirs()
    File(dir, "src/main/java/Capture.java").writeText(
      """
      import java.io.IOException;
      import java.nio.file.*;

      public class Capture {
        public static void main(String[] args) throws IOException {
          Path out = Paths.get(args[1]);
          if (!args[0].isEmpty()) copy(Paths.get(args[0]), out);
          copy(Paths.get(System.getProperty("livery.designs")), out.resolve("designs"));
          Files.writeString(out.resolve("captured-by.txt"), "$marker");
        }

        static void copy(Path from, Path to) throws IOException {
          try (var files = Files.walk(from)) {
            for (Path p : (Iterable<Path>) files::iterator) {
              Path target = to.resolve(from.relativize(p).toString());
              if (Files.isDirectory(p)) Files.createDirectories(target);
              else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
          }
        }
      }
      """.trimIndent()
    )
  }

  private fun run(dir: File, vararg args: String): BuildResult =
    GradleRunner.create().withProjectDir(dir).withPluginClasspath().withArguments(*args, "--stacktrace").build()

  private fun BuildResult.outcome(task: String) = task(task)?.outcome

  @Test fun buildsTheCatalogItCaptures() {
    val dir = project()
    val result = run(dir, "buildLiveryCatalog")
    assertEquals(TaskOutcome.SUCCESS, result.outcome(":captureLiveryCatalog"))
    assertEquals(TaskOutcome.SUCCESS, result.outcome(":buildLiveryCatalog"))
    assertEquals("first", File(dir, "build/livery/captured/captured-by.txt").readText())
    // The designs came from the capture, not the source tree, which has none.
    assertFalse(File(dir, "catalog/designs").exists())
    val manifest = File(dir, "build/generated/livery/assets/livery/manifest.json").readText()
    assertTrue("\"templates\"" in manifest && "banner-classic" in manifest, manifest.take(400))
  }

  @Test fun recapturesOnlyWhenTheCodeOrTheSourceChanges() {
    val dir = project()
    run(dir, "buildLiveryCatalog")
    val again = run(dir, "buildLiveryCatalog")
    assertEquals(TaskOutcome.UP_TO_DATE, again.outcome(":captureLiveryCatalog"))
    assertEquals(TaskOutcome.UP_TO_DATE, again.outcome(":buildLiveryCatalog"))

    capture(dir, marker = "second")
    assertEquals(TaskOutcome.SUCCESS, run(dir, "buildLiveryCatalog").outcome(":captureLiveryCatalog"))
    assertEquals("second", File(dir, "build/livery/captured/captured-by.txt").readText())

    File(dir, "catalog/catalog.json").appendText("\n")
    assertEquals(TaskOutcome.SUCCESS, run(dir, "buildLiveryCatalog").outcome(":captureLiveryCatalog"))
  }

  @Test fun aStaleCaptureIsRemoved() {
    val dir = project()
    run(dir, "captureLiveryCatalog")
    val stale = File(dir, "build/livery/captured/designs/removed-design/in-app.rc").apply { parentFile.mkdirs(); writeText("stale") }
    run(dir, "captureLiveryCatalog", "--rerun-tasks")
    assertFalse(stale.exists(), "a design the entry point no longer writes is gone")
  }

  @Test fun comesFromTheBuildCacheAndTheConfigurationCache() {
    val dir = project()
    run(dir, "buildLiveryCatalog", "--build-cache", "--configuration-cache")
    File(dir, "build").deleteRecursively()
    val cached = run(dir, "buildLiveryCatalog", "--build-cache", "--configuration-cache")
    assertEquals(TaskOutcome.FROM_CACHE, cached.outcome(":captureLiveryCatalog"))
    assertTrue("Reusing configuration cache" in cached.output, cached.output.takeLast(1500))
  }

  @Test fun withoutAnEntryPointTheSourceIsBuiltAsItIs() {
    val dir = project()
    File(dir, "build.gradle.kts").writeText(
      """
      plugins { id("ee.schimke.composeai.livery.catalog") }
      livery { inspect.set(false) }
      """.trimIndent()
    )
    samples.copyRecursively(File(dir, "livery"))
    val result = run(dir, "buildLiveryCatalog")
    assertEquals(TaskOutcome.SKIPPED, result.outcome(":captureLiveryCatalog") ?: TaskOutcome.SKIPPED)
    assertEquals(TaskOutcome.SUCCESS, result.outcome(":buildLiveryCatalog"))
  }
}
