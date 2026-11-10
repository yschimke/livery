package ee.schimke.livery.review

import ee.schimke.livery.publisher.load
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayCommandTest {
  private val sample = File(requireNotNull(System.getProperty("livery.samples")) { "run through Gradle: gradle/sample-catalogs.gradle.kts" }).canonicalFile
  private val out = Files.createTempDirectory("play").toFile()

  private fun play(vararg args: String, env: Map<String, String> = emptyMap()) = PlayCommand().run(args.toList(), env)

  @Test fun aTemplatePlaysWithAnExample() {
    val png = File(out, "on.png")
    val code = play(
      "$sample/designs/tile-default/in-app.rc", "--contract", "$sample/contracts/status-tile.json",
      "--example", "on", "--size", "360x120", "--out", png.path,
    )
    assertEquals(0, code)
    assertTrue(png.length() > 0)
  }

  @Test fun dataThatDoesntBindFails() {
    val data = File(out, "bad.json").apply { writeText("""{ "name": 3 }""") }
    assertEquals(1, play("$sample/designs/tile-default/in-app.rc", "--contract", "$sample/contracts/status-tile.json", "--data", data.path, "--out", File(out, "x.png").path))
  }

  @Test fun aKeptExampleIsNamedByItsLabel() {
    val catalog = Files.createTempDirectory("catalog").toFile().also { sample.copyRecursively(it) }
    File(catalog, "contracts/examples/status-tile").mkdirs()
    File(catalog, "contracts/examples/status-tile/dim-x.json").writeText("""{ "name": "Hall", "state": "Dim", "is_on": true, "level": 0.05 }""")
    val label = ee.schimke.livery.publisher.KeptExamples.load(catalog).getValue("live/status-tile@1").keys.single()
    assertEquals(0, play("$catalog/designs/tile-default/in-app.rc", "--contract", "$catalog/contracts/status-tile.json", "--catalog", catalog.path, "--example", label, "--out", File(out, "k.png").path))
  }

  @Test fun withACatalogItPlaysAndACatalogThatDoesntBuildIsAnError() {
    // --catalog builds the catalog for its images and fonts; one that does not build is an error (2).
    assertEquals(0, play("$sample/designs/tile-default/in-app.rc", "--contract", "$sample/contracts/status-tile.json", "--catalog", sample.path, "--example", "on", "--out", File(out, "c.png").path))
    val broken = Files.createTempDirectory("broken").toFile().also { File(it, "catalog.json").writeText("{") }
    assertEquals(2, play("$sample/designs/tile-default/in-app.rc", "--contract", "$sample/contracts/status-tile.json", "--catalog", broken.path, "--example", "on", "--out", File(out, "d.png").path))
  }

  @Test fun aJavaThatWontStartIsAnErrorNotAFailure() {
    // Everything the widget player needs is there except a Java that runs: exit 2, not 1.
    val lib = Files.createTempDirectory("android-play").toFile()
    val cache = Files.createTempDirectory("cache").toFile()
    File(cache, "robolectric").mkdirs()
    val sdk = java.util.Properties().apply { PlayCommandTest::class.java.getResourceAsStream("/livery-play.properties")!!.use(::load) }.getProperty("robolectric.sdk36")
    File(cache, "robolectric/android-all-instrumented-$sdk.jar").writeText("")
    val code = play(
      "$sample/designs/tile-default/in-app.rc", "--contract", "$sample/contracts/status-tile.json", "--example", "on",
      "--surface", "launcher-widget", "--out", File(out, "w.png").path,
      env = mapOf("LIVERY_ANDROID_PLAY" to lib.path, "LIVERY_CACHE" to cache.path, "LIVERY_JAVA" to "/no/such/java"),
    )
    assertEquals(2, code)
  }

  @Test fun anUnknownSurfaceIsAUsageError() {
    assertEquals(64, play("$sample/designs/tile-default/in-app.rc", "--contract", "$sample/contracts/status-tile.json", "--surface", "toaster"))
  }
}
