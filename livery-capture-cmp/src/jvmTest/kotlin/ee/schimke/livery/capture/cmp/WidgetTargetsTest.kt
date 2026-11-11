package ee.schimke.livery.capture.cmp

import androidx.compose.ui.graphics.Color
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.clickable
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxSize
import ee.schimke.composeai.rccreation.compose.modifier.onTouchDown
import ee.schimke.composeai.rccreation.compose.state.rc
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcHeaderValue
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.livery.capture.CatalogContracts
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.Target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** What a capture for a widget player is held to (`livery-verify`'s CapturedWidgetTest plays one). */
class WidgetTargetsTest {
  private val contract = CatalogContracts.contract(java.io.File(SampleCatalogs.source, "catalog"), "live/status-tile@1")
  private val launcher = Target(Surfaces.LAUNCHER_WIDGET, 7, listOf("widgets"))
  private val wear = Target(Surfaces.WEAR_WIDGET, 7, listOf("glance-wear"), container = "wear:small", theme = "baked:dark")
  private val inApp = Target(Surfaces.IN_APP, 7, listOf("androidx"))

  private fun tile(target: Target) = runBlocking {
    LiveryCapture.capture(target, 200, 100) {
      val v = LiveryValues(contract)
      RemoteColumn(RemoteModifier.fillMaxSize().clickable(v.action("toggle"))) {
        RemoteText(v.string("name"), color = Color.White.rc)
        RemoteText(v.string("state"), color = v.color("theme.primary", Color(0xFFD0BCFF)))
      }
    }
  }

  private fun opcodes(bytes: ByteArray) = RcDocumentCodec.decode(bytes, RcWireLimits()).operations.map { it.opcode }

  @Test fun aLauncherCaptureIsWrittenInWhatTheLauncherReads() {
    val app = opcodes(tile(inApp).bytes)
    assertTrue(RcOpcodes.CORE_TEXT in app, "an app's player gets the text this API writes: $app")
    val widget = tile(launcher)
    val ops = opcodes(widget.bytes)
    assertTrue(RcOpcodes.CORE_TEXT !in ops && RcOpcodes.LAYOUT_TEXT in ops, "a launcher gets TextLayout: $ops")
    assertEquals(listOf("name", "state", "theme.primary"), widget.reads, "and the same named values")
    assertEquals(listOf("toggle"), widget.emits)
    // The widgets profile (0x100) with its experimental operations (0x1), and no measuring while painting.
    val header = RcDocumentCodec.decode(widget.bytes, RcWireLimits()).header.properties.associate { it.key to it.value }
    assertEquals(RcHeaderValue.IntValue(0x101), header[14], "DOC_PROFILES")
    assertEquals(RcHeaderValue.IntValue(0), header[15], "FEATURE_PAINT_MEASURE")
  }

  @Test fun aWearCaptureIsChecked() {
    assertEquals(listOf("toggle"), tile(wear).emits)
  }

  @Test fun whatAWidgetPlayerCantPlayIsRefused() {
    val e = assertFailsWith<IllegalStateException> {
      runBlocking {
        // Glance Wear's player has no touch-down operation.
        LiveryCapture.capture(wear, 200, 100) {
          val v = LiveryValues(contract)
          RemoteColumn(RemoteModifier.fillMaxSize().onTouchDown(v.action("toggle"))) { RemoteText(v.string("name")) }
        }
      }
    }
    assertEquals("the capture wouldn't play as glance-wear on wear-widget: Wear widgets: MODIFIER_TOUCH_DOWN is not supported by Wear widgets", e.message)
  }
}
