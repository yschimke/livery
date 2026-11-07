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
 * warning sign (it reads a measured size where that player evaluates it unmeasured), and its fix.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class PlatformWidgetPlayerTest {
  private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
  private fun fixture(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.readBytes()

  @Test fun aTemplateThePlayerDisablesFails() {
    val outcome = PlatformWidgetPlayer.play(activity, fixture("crashes-on-android-16.rc"), 704, 380)
    val failed = assertIs<PlatformWidgetPlayer.Outcome.Failed>(outcome)
    assertTrue(failed.reason.startsWith("crashed while drawing: RuntimeException: FloatExpression["), failed.reason)
    assertTrue("ArrayIndexOutOfBoundsException" in failed.reason, failed.reason)
  }

  @Test fun itsFixPlays() {
    assertIs<PlatformWidgetPlayer.Outcome.Played>(PlatformWidgetPlayer.play(activity, fixture("plays-on-android-16.rc"), 704, 380))
  }

  @Test fun aDocumentThePlatformCantReadFails() {
    val outcome = PlatformWidgetPlayer.play(activity, fixture("not-a-widget-document.rc"), 400, 200)
    val failed = assertIs<PlatformWidgetPlayer.Outcome.Failed>(outcome)
    assertTrue("Unknown operation" in failed.reason || "couldn't be adapted" in failed.reason, failed.reason)
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
    val failures = WidgetCatalogCheck.launcherWidgets(activity, catalog, density = 2f)
    val examples = listOf("bluetooth", "mp3", "disconnected")
    assertEquals(examples.map { "broken" to it }, failures.map { it.design to it.example }, failures.toString())
    assertTrue(failures.all { it.reason.startsWith("crashed while drawing") }, failures.toString())
  }
}
