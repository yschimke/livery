package ee.schimke.livery.capture.cmp

import androidx.compose.runtime.remember
import ee.schimke.composeai.rccreation.compose.action.combinedAction
import ee.schimke.composeai.rccreation.compose.action.valueChangeAction
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.clickable
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxSize
import ee.schimke.composeai.rccreation.compose.state.MutableRemoteBoolean
import ee.schimke.livery.capture.CatalogContracts
import ee.schimke.livery.capture.DesignFolder
import ee.schimke.livery.instantiate.TemplateInspector
import ee.schimke.livery.protocol.ActionSpec
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.InputSpec
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.Target
import ee.schimke.livery.protocol.ValueType
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

class LiveryCaptureTest {
  private val contract = Contract(
    "demo/card", 1,
    inputs = mapOf(
      "title" to InputSpec(ValueType.String, required = true, default = JsonPrimitive("Title")),
      "is_on" to InputSpec(ValueType.Bool),
      "theme.primary" to InputSpec(ValueType.Color, role = "primary"),
    ),
    actions = mapOf(
      "open" to ActionSpec("navigate"),
      "expand" to ActionSpec("track", args = mapOf("section" to InputSpec(ValueType.String, required = true))),
    ),
  )
  private val launcher = Target(Surfaces.LAUNCHER_WIDGET, 7, listOf("widgets"))

  private suspend fun card() = LiveryCapture.capture(launcher, 200, 100, source = mapOf("design" to "card")) {
    val v = LiveryValues(contract)
    RemoteColumn(RemoteModifier.fillMaxSize().clickable(v.action("open"))) {
      RemoteText(v.string("title"), color = v.color("theme.primary"))
    }
  }

  @Test fun capturesAndDescribesTheTemplate() = runBlocking {
    val t = card()
    assertEquals(listOf("theme.primary", "title"), t.reads)
    assertEquals(listOf("open"), t.emits)
    assertEquals(mapOf("tool" to "capture", "design" to "card"), t.source)
    assertEquals(emptyList(), TemplateInspector.typeProblems(t.bytes, contract), "the publisher's type check passes")
    assertTrue("\"reads\"" in t.sidecar() && "\"launcher-widget\"" in t.sidecar())
  }

  @Test fun oneTapChangesLocalStateAndTracksIt() = runBlocking {
    val t = LiveryCapture.capture(launcher, 200, 100) {
      val v = LiveryValues(contract)
      val expanded = remember { MutableRemoteBoolean(false) }
      RemoteColumn(RemoteModifier.fillMaxSize().clickable(combinedAction(valueChangeAction(expanded, !expanded), v.action("expand", mapOf("section" to "specs"))))) {
        RemoteText(v.string("title"))
      }
    }
    assertEquals(listOf("expand"), t.emits, "the track action is in the document beside the state change")
  }

  @Test fun actionArgumentsAreChecked() {
    assertEquals("demo/card@1 action 'expand' declares no arguments [note]", assertFailsWith<IllegalArgumentException> {
      runBlocking { LiveryCapture.capture(launcher, 200, 100) { LiveryValues(contract).action("expand", mapOf("section" to "a", "note" to "x")) } }
    }.message)
    assertEquals("demo/card@1 action 'expand': missing argument 'section'", assertFailsWith<IllegalArgumentException> {
      runBlocking { LiveryCapture.capture(launcher, 200, 100) { LiveryValues(contract).action("expand") } }
    }.message)
    Unit
  }

  @Test fun refusesWhatTheContractDoesntDeclare() {
    val v = LiveryValues(contract)
    assertEquals("demo/card@1 declares no value 'subtitle'", assertFailsWith<IllegalArgumentException> { v.string("subtitle") }.message)
    assertEquals("demo/card@1 declares 'is_on' as bool, not string", assertFailsWith<IllegalArgumentException> { v.string("is_on") }.message)
    assertFailsWith<IllegalArgumentException> {
      runBlocking { LiveryCapture.capture(launcher, 200, 100) { LiveryValues(contract).action("share") } }
    }
    Unit
  }

  @Test fun readsAContractFromACatalog() {
    val c = CatalogContracts.contract(java.io.File("../samples/catalog"), "live/status-tile@1")
    assertEquals("toggle" in c.actions, true)
    assertFailsWith<IllegalArgumentException> { CatalogContracts.contract(java.io.File("../samples/catalog"), "live/nope@1") }
  }

  @Test fun aDesignFolderStaysCurrent() = runBlocking {
    val dir = Files.createTempDirectory("design").toFile()
    val folder = DesignFolder(dir)
    val templates = mapOf("launcher" to card())
    assertEquals(listOf("launcher.rc is missing", "launcher.json is missing"), folder.check(templates))
    folder.write(templates)
    assertEquals(emptyList(), folder.check(templates))
    dir.resolve("old.rc").writeBytes(byteArrayOf(1))
    assertEquals(listOf("old.rc is no longer captured"), folder.check(templates))
    folder.write(templates)
    assertEquals(emptyList(), folder.check(templates))
    // A template named like the design's own files is refused, never written over design.json.
    assertFailsWith<IllegalArgumentException> { folder.write(mapOf("design" to templates.getValue("launcher"))) }
    assertFailsWith<IllegalArgumentException> { folder.write(mapOf("../up" to templates.getValue("launcher"))) }
    Unit
  }
}
