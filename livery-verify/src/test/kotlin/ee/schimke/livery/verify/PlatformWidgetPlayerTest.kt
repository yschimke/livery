package ee.schimke.livery.verify

import android.app.Activity
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Templates from a real app (Cadence's headphone widget): one that Android 16's player shows as a
 * warning sign when handed it as it is (it reads a measured size where that player evaluates it
 * unmeasured), and its fix. Published through rc-players (2.31.1 and later), as a widget provider
 * publishes, the first is recovered and plays too.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class PlatformWidgetPlayerTest {
  private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
  private fun fixture(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.readBytes()

  @Test fun aTemplateThePlayerDisablesFails() {
    val outcome = PlatformWidgetPlayer.play(activity, fixture("crashes-on-android-16.rc"), 704, 380, recover = false)
    val failed = assertIs<PlatformWidgetPlayer.Outcome.Failed>(outcome)
    assertTrue(failed.reason.startsWith("crashed while drawing: RuntimeException: FloatExpression["), failed.reason)
    assertTrue("ArrayIndexOutOfBoundsException" in failed.reason, failed.reason)
  }

  @Test fun itsFixPlays() {
    assertIs<PlatformWidgetPlayer.Outcome.Played>(PlatformWidgetPlayer.play(activity, fixture("plays-on-android-16.rc"), 704, 380))
  }

  /** What rc-players recovers for the launcher plays: the crash, and text the player has no operation for. */
  @Test fun whatThePublishingPathRecoversPlays() {
    assertIs<PlatformWidgetPlayer.Outcome.Played>(PlatformWidgetPlayer.play(activity, fixture("crashes-on-android-16.rc"), 704, 380))
    val text = assertIs<PlatformWidgetPlayer.Outcome.Played>(PlatformWidgetPlayer.play(activity, fixture("not-a-widget-document.rc"), 400, 200))
    val pixels = IntArray(400 * 200).also { text.image.getPixels(it, 0, 400, 0, 0, 400, 200) }
    assertTrue(pixels.count { it ushr 24 != 0 } > 1000, "the recovered document draws")
  }

  @Test fun aDocumentThePlatformCantReadFails() {
    val outcome = PlatformWidgetPlayer.play(activity, fixture("not-a-widget-document.rc"), 400, 200, recover = false)
    val failed = assertIs<PlatformWidgetPlayer.Outcome.Failed>(outcome)
    assertTrue("Unknown operation" in failed.reason || "couldn't be adapted" in failed.reason, failed.reason)
  }

  @Test fun aPathThatIsntACatalogFails() {
    val missing = File(Files.createTempDirectory("x").toFile(), "nope")
    kotlin.test.assertFailsWith<IllegalArgumentException> { runBlocking { WidgetCatalogCheck.launcherWidgets(activity, missing) } }
  }

  @Test fun theCatalogsOwnImagesResolve() = runBlocking {
    val catalog = Files.createTempDirectory("catalog").toFile()
    val png = byteArrayOf(1, 2, 3)
    File(catalog, "designs/d/assets").mkdirs()
    File(catalog, "designs/d/assets/hero.png").writeBytes(png)
    val sha = java.security.MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }
    val resolve = WidgetCatalogCheck.images(catalog) { ref, _, _ -> if (ref == "https://x/y.png") byteArrayOf(9) else null }
    assertTrue(resolve("asset:$sha", 10, 10).contentEquals(png))
    assertTrue(resolve("https://x/y.png", 10, 10).contentEquals(byteArrayOf(9)), "the caller's images")
    assertEquals(null, resolve("asset:" + "0".repeat(64), 10, 10))
  }

  @Test fun theCatalogCheckReportsEachTemplateAndExampleThatFails() = runBlocking {
    val catalog = Files.createTempDirectory("catalog").toFile()
    File(catalog, "contracts").mkdirs()
    File(catalog, "contracts/headphone-controls.json").writeBytes(fixture("headphone-controls.json"))
    for ((design, doc) in listOf("broken" to "crashes-on-android-16.rc", "fixed" to "plays-on-android-16.rc")) {
      val dir = File(catalog, "designs/$design").apply { mkdirs() }
      File(dir, "design.json").writeText("""{ "id": "$design", "contract": "cadence/headphone-controls@1", "title": "$design" }""")
      File(dir, "launcher.rc").writeBytes(fixture(doc))
      File(dir, "launcher.json").writeText("""{ "target": { "surface": "launcher-widget", "rcApiLevel": 7, "profiles": ["widgets"], "size": { "minWidthDp": 352, "minHeightDp": 190 } } }""")
      // Not a launcher widget: not played here.
      File(dir, "in-app.json").writeText("""{ "target": { "surface": "in-app", "rcApiLevel": 7 } }""")
    }
    // Older preview data the catalog keeps is played too.
    File(catalog, "contracts/examples/headphone-controls").mkdirs()
    File(catalog, "contracts/examples/headphone-controls/playing-0badf00d.json").writeText(
      """{ "title": "Old track", "short_title": "Old", "playing": true, "mode": 1 }""")
    // Published as a widget provider publishes, both designs play: the broken one is recovered.
    assertEquals(emptyList(), WidgetCatalogCheck.launcherWidgets(activity, catalog, density = 2f))
    val failures = WidgetCatalogCheck.launcherWidgets(activity, catalog, density = 2f, recover = false)
    assertEquals(listOf("bluetooth", "mp3", "disconnected"), failures.take(3).map { it.example }, failures.toString())
    assertEquals(4, failures.size, failures.toString())
    assertTrue(failures.last().example.startsWith("playing (kept "), failures.toString())
    assertTrue(failures.all { it.design == "broken" }, failures.toString())
    assertTrue(failures.all { it.reason.startsWith("crashed while drawing") }, failures.toString())
  }
}
