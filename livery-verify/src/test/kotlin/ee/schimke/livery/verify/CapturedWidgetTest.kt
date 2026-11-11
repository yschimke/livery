@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.livery.verify

import android.app.Activity
import androidx.compose.remote.creation.compose.layout.RemoteArrangement as AndroidxArrangement
import androidx.compose.remote.creation.compose.layout.RemoteColumn as AndroidxColumn
import androidx.compose.remote.creation.compose.layout.RemoteText as AndroidxText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier as AndroidxModifier
import androidx.compose.remote.creation.compose.modifier.background as androidxBackground
import androidx.compose.remote.creation.compose.modifier.clickable as androidxClickable
import androidx.compose.remote.creation.compose.modifier.fillMaxSize as androidxFillMaxSize
import androidx.compose.remote.creation.compose.modifier.padding as androidxPadding
import androidx.compose.remote.creation.compose.state.rc as androidxRc
import androidx.compose.remote.creation.compose.state.rdp as androidxRdp
import androidx.compose.remote.creation.compose.state.rsp as androidxRsp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import ee.schimke.composeai.rccreation.compose.layout.RemoteArrangement
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.background
import ee.schimke.composeai.rccreation.compose.modifier.clickable
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxSize
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.state.rc
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rsp
import ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.livery.capture.CapturedTemplate
import ee.schimke.livery.capture.CatalogContracts
import ee.schimke.livery.capture.DesignFolder
import ee.schimke.livery.capture.LiveryCapture as AndroidxCapture
import ee.schimke.livery.capture.LiveryValues as AndroidxValues
import ee.schimke.livery.capture.cmp.LiveryCapture
import ee.schimke.livery.capture.cmp.LiveryValues
import ee.schimke.livery.client.BindResult
import ee.schimke.livery.client.ContractBinder
import ee.schimke.livery.instantiate.InstantiateResult
import ee.schimke.livery.instantiate.TemplateInstantiator
import ee.schimke.livery.protocol.SizeRange
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.Target
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
 * A launcher widget template captured through each binding, played on Android 16's own widget
 * player the way a widget provider publishes it: what makes a `widgets` capture from
 * `livery-capture-cmp`, which has no AndroidX `Profile` to capture with, a document the launcher
 * accepts. The same tile is written twice, once per creation API; an app can hold both.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class CapturedWidgetTest {
  private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
  private val samples = File("../samples/catalog")
  private val contract = CatalogContracts.contract(samples, "live/status-tile@1")
  private val launcher = Target(Surfaces.LAUNCHER_WIDGET, 7, listOf("widgets"), size = SizeRange(minWidthDp = 200, minHeightDp = 100))

  private fun cmpTile(): CapturedTemplate = runBlocking {
    LiveryCapture.capture(launcher, 200, 100) {
      val v = LiveryValues(contract)
      RemoteColumn(
        RemoteModifier.fillMaxSize().background(Color(0xFF1D1B20).rc).clickable(v.action("toggle")).padding(12.rdp),
        verticalArrangement = RemoteArrangement.spacedBy(4.rdp),
      ) {
        RemoteText(v.string("name"), color = Color.White.rc, fontSize = 16.rsp, fontWeight = RemoteFontWeight.Bold)
        RemoteText(v.string("state"), color = v.color("theme.primary", Color(0xFFD0BCFF)), fontSize = 14.rsp)
      }
    }
  }

  private fun androidxTile(): CapturedTemplate = runBlocking {
    AndroidxCapture.capture(activity, launcher, 200, 100) {
      val v = AndroidxValues(contract)
      AndroidxColumn(
        AndroidxModifier.androidxFillMaxSize().androidxBackground(Color(0xFF1D1B20).androidxRc).androidxClickable(v.action("toggle")).androidxPadding(12.androidxRdp),
        verticalArrangement = AndroidxArrangement.spacedBy(4.androidxRdp),
      ) {
        AndroidxText(v.string("name"), color = Color.White.androidxRc, fontSize = 16.androidxRsp, fontWeight = FontWeight.Bold)
        AndroidxText(v.string("state"), color = v.color("theme.primary", Color(0xFFD0BCFF)), fontSize = 14.androidxRsp)
      }
    }
  }

  /** A catalog source tree holding [template] as `tile/launcher`, with the sample contract and its examples. */
  private fun catalogOf(template: CapturedTemplate): File {
    val catalog = Files.createTempDirectory("catalog").toFile()
    File(catalog, "contracts").mkdirs()
    File(samples, "contracts/status-tile.json").copyTo(File(catalog, "contracts/status-tile.json"))
    val design = File(catalog, "designs/tile").apply { mkdirs() }
    File(design, "design.json").writeText("""{ "id": "tile", "contract": "live/status-tile@1", "title": "Tile" }""")
    DesignFolder(design).write(mapOf("launcher" to template))
    return catalog
  }

  private fun played(template: CapturedTemplate, example: String, density: Float): IntArray = runBlocking {
    val bindings = assertIs<BindResult.Ok>(ContractBinder.bind(contract, contract.examples.getValue(example))).bindings
    val (w, h) = (200 * density).toInt() to (100 * density).toInt()
    val document = assertIs<InstantiateResult.Ok>(TemplateInstantiator { _, _, _ -> null }.instantiate(template.bytes, bindings, w, h, numericActions = true)).bytes
    val outcome = assertIs<PlatformWidgetPlayer.Outcome.Played>(PlatformWidgetPlayer.play(activity, document, w, h), "$example plays")
    IntArray(w * h).also { outcome.image.getPixels(it, 0, w, 0, 0, w, h) }
  }

  @Test fun aCmpCapturedLauncherWidgetPlaysOnThePlatformPlayer() = runBlocking {
    val tile = cmpTile()
    assertEquals(listOf("name", "state", "theme.primary"), tile.reads)
    assertEquals(listOf("toggle"), tile.emits)
    // Each of the contract's examples, written in as the widget provider writes it, then played.
    assertEquals(emptyList(), WidgetCatalogCheck.launcherWidgets(activity, catalogOf(tile), density = 2f))
    // And as it is, with none of rc-widget-launcher's recovery before the player.
    assertEquals(emptyList(), WidgetCatalogCheck.launcherWidgets(activity, catalogOf(tile), density = 2f, recover = false))
    // Text is the TextLayout Android 16 reads, never the CoreText (239) it fails a document on.
    val operations = RcDocumentCodec.decode(tile.bytes, RcWireLimits()).operations.map { it.opcode }
    assertTrue(RcOpcodes.CORE_TEXT !in operations && RcOpcodes.LAYOUT_TEXT in operations, operations.toString())
  }

  @Test fun itDrawsWhatTheAndroidxCaptureDraws() {
    val report = StringBuilder()
    for (example in contract.examples.keys) for (density in listOf(1f, 2f)) {
      val cmp = played(cmpTile(), example, density)
      val androidx = played(androidxTile(), example, density)
      val text = androidx.count { it == 0xFFFFFFFF.toInt() }
      assertTrue(text > 20, "$example @${density}x: the AndroidX capture drew its white text ($text pixels)")
      val differing = cmp.indices.count { cmp[it] != androidx[it] }
      report.appendLine("$example @${density}x: $differing of ${cmp.size} pixels differ ($text white text pixels)")
      assertEquals(0, differing, "$example @${density}x: the two captures draw differently on the platform player\n$report")
    }
    print(report)
  }
}
