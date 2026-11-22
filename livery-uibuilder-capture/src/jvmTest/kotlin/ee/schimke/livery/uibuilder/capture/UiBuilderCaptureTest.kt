package ee.schimke.livery.uibuilder.capture

import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.livery.publisher.CatalogBuilder
import ee.schimke.livery.publisher.Layout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/** The test's UI Builder catalog, registered in META-INF/services as an app registers its own. */
class TestCatalog : UiBuilderCatalog {
  override val systemIds = setOf("test-cards")

  override val components: Map<String, UiComponent> = mapOf(
    "test-cards/text" to { node, modifier -> RemoteText(node.remoteString("text"), modifier) },
  )

  /** The designs preview with `sample`; the contract calls it `name`. */
  override fun prepare(document: JsonObject): JsonObject =
    JsonObject(document + ("stateVariables" to JsonObject(
      document["stateVariables"]!!.jsonObject.mapKeys { (k, _) -> if (k == "sample") "name" else k }
    )) + ("nodes" to JsonObject(document["nodes"]!!.jsonObject.mapValues { (_, node) ->
      JsonObject(node.jsonObject.mapValues { (key, value) ->
        if (key != "properties") value
        else JsonObject(value.jsonObject.mapValues { (_, p) ->
          if (p.jsonObject["variable"] == JsonPrimitive("sample")) JsonObject(p.jsonObject + ("variable" to JsonPrimitive("name"))) else p
        })
      })
    })))
}

class UiBuilderCaptureTest {
  private val contract = """
    { "id": "demo/greeting", "major": 1, "minor": 0,
      "inputs": { "name": { "type": "string", "required": true, "default": "World" },
                  "mood": { "type": "string" } } }
  """.trimIndent()

  private fun document(variable: String = "sample", text: String = """{ "type": "state", "variable": "$variable" }""") = """
    { "schema": "compose-ui-builder-document/v1", "id": "greeting", "title": "Greeting", "revision": 3,
      "catalogPin": { "systemId": "test-cards" },
      "environment": { "widthDp": 200, "heightDp": 80 },
      "stateVariables": {
        "$variable": { "type": "text", "valueType": "string", "initialValue": "Ada", "persistence": "host" },
        "local": { "type": "text", "valueType": "string", "initialValue": "Hello", "persistence": "local" } },
      "roots": ["root"],
      "nodes": {
        "root": { "id": "root", "componentId": "layout/column", "properties": { "verticalSpacingDp": { "type": "float", "value": 4 } },
                  "modifiers": [ { "type": "fillMaxWidth" }, { "type": "padding", "allDp": 8 } ],
                  "slots": { "children": ["hello", "name"] } },
        "hello": { "id": "hello", "componentId": "test-cards/text", "properties": { "text": { "type": "state", "variable": "local" } }, "modifiers": [], "slots": {} },
        "name": { "id": "name", "componentId": "test-cards/text", "properties": { "text": $text }, "modifiers": [], "slots": {} } } }
  """.trimIndent()

  private fun tree(uid: String = document()): File {
    val src = Files.createTempDirectory("uid-src").toFile()
    File(src, "catalog.json").writeText("""{ "catalog": "demo", "channel": "main" }""")
    File(src, "contracts").mkdirs()
    File(src, "contracts/greeting.json").writeText(contract)
    File(src, "designs/greeting").mkdirs()
    File(src, "designs/greeting/design.json").writeText("""{ "id": "greeting", "contract": "demo/greeting@1", "title": "Greeting" }""")
    File(src, "designs/greeting/in-app.uid").writeText(uid)
    File(src, "designs/greeting/launcher.uid").writeText(uid)
    File(src, "designs/greeting/launcher.target.json").writeText("""{ "surface": "launcher-widget", "rcApiLevel": 7, "profiles": ["widgets"] }""")
    return src
  }

  @Test fun theCatalogIsFoundOnTheClasspath() {
    assertEquals(listOf("TestCatalog"), UiBuilderCapture.catalogs().map { it::class.simpleName })
  }

  @Test fun capturesEachUidAsATemplateThatBuilds() {
    val out = Files.createTempDirectory("uid-out").toFile()
    val captured = UiBuilderCapture.captureTree(tree(), out)
    assertEquals(listOf("greeting/in-app", "greeting/launcher"), captured)
    val dir = File(out, "designs/greeting")
    assertEquals(listOf("design.json", "in-app.json", "in-app.rc", "launcher.json", "launcher.rc"), dir.list()!!.sorted())
    val sidecar = File(dir, "in-app.json").readText()
    // The host input reads the contract's value, renamed by the catalog's prepare; the local one is a constant.
    assertContains(sidecar, "\"reads\": [\n        \"name\"\n    ]")
    assertContains(sidecar, "\"tool\": \"ui-builder\"")
    assertContains(sidecar, "\"revision\": \"3\"")
    assertContains(File(dir, "launcher.json").readText(), "\"launcher-widget\"")
    val built = CatalogBuilder(inspect = CatalogBuilder.INSPECT).build(FileSystem.SYSTEM, out.toOkioPath(), Layout.Static)
    assertEquals(listOf("greeting"), built.manifest.designs.map { it.id })
  }

  @Test fun aValueTheContractDoesNotDeclareFailsTheCapture() {
    val e = assertFailsWith<IllegalArgumentException> {
      UiBuilderCapture.captureTree(tree(document(variable = "nickname")), Files.createTempDirectory("uid-out").toFile())
    }
    assertContains(e.message!!, "declares no value 'nickname'")
  }

  @Test fun anUnregisteredComponentIsReportedByNode() {
    val uid = document().replace("\"componentId\": \"test-cards/text\", \"properties\": { \"text\": { \"type\": \"state\", \"variable\": \"local\" }",
      "\"componentId\": \"test-cards/chart\", \"properties\": { \"text\": { \"type\": \"state\", \"variable\": \"local\" }")
    val design = UiBuilderCapture.design(uid, UiBuilderCapture.catalogs())
    assertEquals(listOf("hello: no component `test-cards/chart` is registered"), design.validate())
  }

  @Test fun aSourceOfCatalogsIsCapturedOneByOne() {
    val parent = Files.createTempDirectory("uid-catalogs").toFile()
    tree().copyRecursively(File(parent, "brand-a"))
    val out = Files.createTempDirectory("uid-out").toFile()
    assertEquals(listOf("brand-a/greeting/in-app", "brand-a/greeting/launcher"), UiBuilderCapture.captureTree(parent, out))
    assertTrue(File(out, "brand-a/designs/greeting/in-app.rc").isFile)
  }
}
