package ee.schimke.livery.capture.cmp

import androidx.compose.ui.graphics.Color
import ee.schimke.composeai.rccreation.common.RcPlatformProfiles
import ee.schimke.composeai.rccreation.common.RcProfiles
import ee.schimke.composeai.rccreation.compose.layout.RemoteArrangement
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteCanvas
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteFlowRow
import ee.schimke.composeai.rccreation.compose.layout.RemoteOffset
import ee.schimke.composeai.rccreation.compose.layout.RemoteSize
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.background
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.modifier.clickable
import ee.schimke.composeai.rccreation.compose.modifier.drawWithContent
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxSize
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rccreation.compose.modifier.height
import ee.schimke.composeai.rccreation.compose.modifier.onTouchDown
import ee.schimke.composeai.rccreation.compose.state.RemotePaint
import ee.schimke.composeai.rccreation.compose.state.rc
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rf
import ee.schimke.composeai.rcplayer.edit.RcProfiles as RcEditProfiles
import ee.schimke.composeai.rcplayer.edit.RcTargetProfile
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcHeaderValue
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.composeai.rcwidget.creation.RcCaptureProfiles
import ee.schimke.composeai.rcwidget.creation.toCaptureProfile
import ee.schimke.livery.capture.CatalogContracts
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.Target
import okio.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
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

  private fun decode(bytes: ByteString): RcDocument = RcDocumentCodec.decode(bytes.toByteArray(), RcWireLimits())

  private fun opcodes(bytes: ByteString) = decode(bytes).operations.map { it.opcode }

  private fun header(bytes: ByteString) = decode(bytes).header.properties.associate { it.key to it.value }

  @Test fun aTargetsProfilesChooseTheCaptureProfile() {
    assertSame(RcCaptureProfiles.launcherWidgets, launcher.captureProfile())
    assertSame(RcCaptureProfiles.launcherWidgets, Target(Surfaces.NOTIFICATION, 7, listOf("widgets")).captureProfile())
    assertSame(RcCaptureProfiles.wearWidgets, wear.captureProfile())
    assertSame(RcPlatformProfiles.ANDROIDX, inApp.captureProfile())
    assertSame(RcCaptureProfiles.androidXExperimental, Target(Surfaces.IN_APP, 7, listOf("androidx", "experimental")).captureProfile())
  }

  @Test fun aLauncherCaptureIsWrittenInWhatTheLauncherReads() {
    val app = opcodes(tile(inApp).bytes)
    assertTrue(RcOpcodes.CORE_TEXT in app, "an app's player gets the text this API writes: $app")
    val widget = tile(launcher)
    val ops = opcodes(widget.bytes)
    assertTrue(RcOpcodes.CORE_TEXT !in ops && RcOpcodes.LAYOUT_TEXT in ops, "a launcher gets TextLayout: $ops")
    assertEquals(listOf("name", "state", "theme.primary"), widget.reads, "and the same named values")
    assertEquals(listOf("toggle"), widget.emits)
    // The widgets profile (0x100) with its experimental operations (0x1), and no measuring while painting.
    val header = header(widget.bytes)
    assertEquals(RcHeaderValue.IntValue(0x101), header[14], "DOC_PROFILES")
    assertEquals(RcHeaderValue.IntValue(0), header[15], "FEATURE_PAINT_MEASURE")
  }

  /** The template reads named values and a density, which Android 16 only reads literal: the host's job, not the capture's. */
  @Test fun aTemplateIsNotHeldToWhatItsHostMakesLiteral() {
    val padded = runBlocking {
      LiveryCapture.capture(launcher, 200, 100) {
        val v = LiveryValues(contract)
        RemoteColumn(
          RemoteModifier.fillMaxSize().background(v.color("theme.primary", Color.Black)).padding(8.rdp).clickable(v.action("toggle")),
          verticalArrangement = RemoteArrangement.spacedBy(8.rdp),
        ) { RemoteText(v.string("name")) }
      }
    }
    assertEquals(listOf("name", "theme.primary"), padded.reads)
    assertEquals(listOf("toggle"), padded.emits)
  }

  @Test fun aWearCaptureIsWrittenForGlanceWear() {
    val widget = tile(wear)
    assertEquals(listOf("toggle"), widget.emits)
    assertEquals(RcHeaderValue.IntValue(RcProfiles.PROFILE_WEAR_WIDGETS), header(widget.bytes)[14])
    assertTrue(RcOpcodes.CORE_TEXT in opcodes(widget.bytes), "Glance Wear's allow-list keeps CoreText (a launcher's does not)")
  }

  private fun message(block: () -> Unit): String? {
    var cause: Throwable = assertFailsWith<Throwable> { block() }
    while (cause.cause != null) cause = cause.cause!!
    return cause.message
  }

  @Test fun whatAWidgetPlayerCantPlayIsRefusedByTheWriter() {
    // Glance Wear's player has no touch-down operation.
    val touchDown = message {
      runBlocking {
        LiveryCapture.capture(wear, 200, 100) {
          val v = LiveryValues(contract)
          RemoteColumn(RemoteModifier.fillMaxSize().onTouchDown(v.action("toggle"))) { RemoteText(v.string("name")) }
        }
      }
    }
    assertTrue(touchDown!!.matches(Regex("Operation \\d+ is not supported for this version")), touchDown)
    // A flow row is AndroidX's experimental operation (240): the default in-app profile refuses it, the experimental one doesn't.
    fun flow(target: Target) = message {
      runBlocking { LiveryCapture.capture(target, 200, 100) { RemoteFlowRow(RemoteModifier.fillMaxSize()) { RemoteText(LiveryValues(contract).string("name")) } } }
    }
    assertEquals("Operation 240 is not supported for this version", flow(inApp))
    assertEquals("Operation 240 is not supported for this version", flow(launcher))
  }

  @Test fun aFlowRowCapturesForATargetThatDeclaresExperimental() {
    val experimental = Target(Surfaces.IN_APP, 7, listOf("androidx", "experimental"))
    val t = runBlocking { LiveryCapture.capture(experimental, 200, 100) { RemoteFlowRow(RemoteModifier.fillMaxSize()) { RemoteText(LiveryValues(contract).string("name")) } } }
    assertTrue(RcOpcodes.LAYOUT_FLOW in opcodes(t.bytes))
    assertEquals(RcHeaderValue.IntValue(RcProfiles.PROFILE_ANDROIDX or RcProfiles.PROFILE_EXPERIMENTAL), header(t.bytes)[14])
  }

  private fun relativeTime(target: Target, profile: ee.schimke.composeai.rccreation.common.Profile? = null) = runBlocking {
    val content: @androidx.compose.runtime.Composable () -> Unit = { RemoteText(LiveryValues(contract).string("name").substring(1)) }
    if (profile == null) LiveryCapture.capture(target, 200, 100, content = content) else LiveryCapture.capture(target, 200, 100, profile = profile, content = content)
  }

  /**
   * `RcCaptureProfiles.launcherWidgets` is Android 16's level 6 table, which has no `TextSubtext`
   * (182): a template that cuts a named string fails the capture rather than the launcher. A level 7
   * launcher takes it (`systemThemedLauncherWidgets`, or the level 7 table less what Android 16 lacks).
   */
  @Test fun aSubstringNeedsALevelSevenLauncher() {
    assertEquals("Operation 182 is not supported for this version", message { relativeTime(launcher) })
    val systemThemed = relativeTime(launcher, RcCaptureProfiles.systemThemedLauncherWidgets)
    assertTrue(RcOpcodes.TEXT_SUBTEXT in opcodes(systemThemed.bytes))
    val level7 = RcTargetProfile.of(7, RcEditProfiles.WIDGETS or RcEditProfiles.EXPERIMENTAL)
      .toCaptureProfile(without = setOf(RcOpcodes.CORE_TEXT, RcOpcodes.TEXT_STYLE, RcOpcodes.LAYOUT_FLOW), headerTags = listOf(ee.schimke.composeai.rccreation.common.HTag(ee.schimke.composeai.rccreation.common.Header.FEATURE_PAINT_MEASURE, 0)))
    val own = relativeTime(launcher, level7)
    assertTrue(RcOpcodes.TEXT_SUBTEXT in opcodes(own.bytes) && RcOpcodes.CORE_TEXT !in opcodes(own.bytes))
  }

  /** `drawWithContent` writes a `DrawContent` into the component's canvas operations, which Android 16's player paints until the launcher dies. */
  @Test fun aDrawWithContentIsRefusedForAnAndroid16Launcher() {
    fun capture(target: Target) = runBlocking { LiveryCapture.capture(target, 200, 100) { RemoteBox(RemoteModifier.fillMaxSize().drawWithContent { drawContent() }) { RemoteText(LiveryValues(contract).string("name")) } } }
    val e = assertFailsWith<IllegalStateException> { capture(launcher) }
    assertTrue(e.message!!.startsWith("the capture wouldn't play as widgets on launcher-widget: "), e.message)
    // Nothing else reads it recursively: an app's player takes it.
    assertTrue(RcOpcodes.DRAW_CONTENT in opcodes(capture(inApp).bytes))
  }

  /** Where each text named `wght` is: its index in the document and how many containers deep. */
  private fun axisNames(bytes: ByteString): List<Pair<Int, Int>> {
    var depth = 0
    val found = ArrayList<Pair<Int, Int>>()
    for ((i, op) in decode(bytes).operations.withIndex()) {
      if (op is RcTextData && op.text == "wght") found += i to depth
      if (op.opcode in RcOpcodes.CONTAINER_STARTS) depth++
      if (op.opcode == RcOpcodes.CONTAINER_END) depth--
    }
    return found
  }

  /**
   * Without `CoreText` the writer names a paint's font axis as a `wght` text once, in the first
   * canvas that paints, and later canvases read it (androidx-main's `PaintTracker`; AndroidX's
   * alpha23 writes none). A launcher host has to play that: `livery-verify`'s CapturedWidgetTest
   * does, on Android 16's player.
   */
  @Test fun aLauncherProfileDeclaresTheFontAxisInTheFirstCanvasOnly() {
    fun twoCanvases(target: Target) = runBlocking {
      LiveryCapture.capture(target, 200, 100) {
        RemoteColumn(RemoteModifier.fillMaxSize()) {
          repeat(2) {
            RemoteCanvas(RemoteModifier.fillMaxWidth().height(20.rdp)) { drawRect(RemotePaint(), RemoteOffset(0f.rf, 0f.rf), RemoteSize(width, height)) }
          }
        }
      }
    }
    val axes = axisNames(twoCanvases(launcher).bytes)
    assertEquals(1, axes.size, "declared once: $axes")
    assertTrue(axes.single().second > 0, "inside a canvas, not ahead of the layout: $axes")
    assertEquals(emptyList(), axisNames(twoCanvases(inApp).bytes), "a profile with CoreText names no axis")
  }
}
