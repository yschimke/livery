package ee.schimke.livery.gradle

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

/**
 * `livery { capture { uiBuilder() } }`: a catalog source tree's `.uid` templates captured by
 * livery-uibuilder-capture's entry point, with no capture code in the project, then built. The
 * design uses only the UI Builder frame's own layouts, so it needs no app catalog.
 */
class UiBuilderCaptureTest {
  private val captureClasspath = requireNotNull(System.getProperty("livery.uiBuilderCapture")) { "run through Gradle" }

  private fun project(): File = Files.createTempDirectory("uid-capture").toFile().apply {
    val src = File(this, "catalog")
    File(src, "contracts").mkdirs()
    File(src, "designs/panel").mkdirs()
    File(src, "catalog.json").writeText("""{ "catalog": "demo", "channel": "main" }""")
    File(src, "contracts/panel.json").writeText("""{ "id": "demo/panel", "major": 1, "minor": 0, "inputs": {} }""")
    File(src, "designs/panel/design.json").writeText("""{ "id": "panel", "contract": "demo/panel@1", "title": "Panel" }""")
    File(src, "designs/panel/in-app.uid").writeText(
      """
      { "schema": "compose-ui-builder-document/v1", "id": "panel", "revision": 7,
        "environment": { "widthDp": 120, "heightDp": 60 },
        "roots": ["root"],
        "nodes": {
          "root": { "id": "root", "componentId": "layout/column", "properties": {}, "modifiers": [ { "type": "fillMaxSize" } ], "slots": { "children": ["row"] } },
          "row": { "id": "row", "componentId": "layout/row", "properties": {}, "modifiers": [], "slots": {} } } }
      """.trimIndent()
    )
    File(this, "settings.gradle.kts").writeText("rootProject.name = \"app\"")
    val classpath = captureClasspath.split(File.pathSeparator).joinToString(", ") { "\"${it.replace("\\", "/")}\"" }
    File(this, "build.gradle.kts").writeText(
      """
      plugins { id("ee.schimke.composeai.livery.catalog") }
      dependencies { "liveryUiBuilderCapture"(files($classpath)) }
      livery {
        source.set(layout.projectDirectory.dir("catalog"))
        capture { uiBuilder() }
      }
      """.trimIndent()
    )
  }

  /** A source tree another task writes: the capture depends on that task and reads the tree it wrote. */
  @Test fun capturesATreeAnotherTaskGenerates() {
    val dir = project()
    File(dir, "catalog").renameTo(File(dir, "template"))
    File(dir, "build.gradle.kts").writeText(
      File(dir, "build.gradle.kts").readText().replace(
        "source.set(layout.projectDirectory.dir(\"catalog\"))",
        "source.set(layout.dir(generate.map { it.destinationDir }))",
      ).replace(
        "livery {",
        "val generate = tasks.register<Sync>(\"generateSource\") { from(\"template\"); into(layout.buildDirectory.dir(\"gen\")) }\nlivery {",
      )
    )
    val result = GradleRunner.create().withProjectDir(dir).withPluginClasspath()
      .withArguments("buildLiveryCatalog", "--configuration-cache", "--stacktrace").build()
    assertEquals(TaskOutcome.SUCCESS, result.task(":generateSource")?.outcome)
    assertEquals(TaskOutcome.SUCCESS, result.task(":captureLiveryCatalog")?.outcome)
    assertTrue(File(dir, "build/livery/captured/designs/panel/in-app.rc").isFile)
  }

  @Test fun capturesTheUidTemplatesAndBuildsThem() {
    val dir = project()
    val result = GradleRunner.create().withProjectDir(dir).withPluginClasspath()
      .withArguments("buildLiveryCatalog", "--configuration-cache", "--stacktrace").build()
    assertEquals(TaskOutcome.SUCCESS, result.task(":captureLiveryCatalog")?.outcome)
    assertEquals(TaskOutcome.SUCCESS, result.task(":buildLiveryCatalog")?.outcome)
    val captured = File(dir, "build/livery/captured/designs/panel")
    assertEquals(listOf("design.json", "in-app.json", "in-app.rc"), captured.list()!!.sorted())
    assertContains(File(captured, "in-app.json").readText(), "\"tool\": \"ui-builder\"")
    assertTrue(File(dir, "build/generated/livery/assets/livery/manifest.json").isFile)
    val again = GradleRunner.create().withProjectDir(dir).withPluginClasspath()
      .withArguments("buildLiveryCatalog", "--configuration-cache").build()
    assertEquals(TaskOutcome.UP_TO_DATE, again.task(":captureLiveryCatalog")?.outcome)
  }
}
