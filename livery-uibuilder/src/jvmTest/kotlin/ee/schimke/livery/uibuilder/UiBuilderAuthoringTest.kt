package ee.schimke.livery.uibuilder

import kotlinx.coroutines.runBlocking
import ee.schimke.livery.mcp.local
import com.sun.net.httpserver.HttpServer
import ee.schimke.livery.mcp.LiveryMcp
import ee.schimke.livery.mcp.SourceTree
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.InputSpec
import ee.schimke.livery.protocol.ListSpec
import ee.schimke.livery.protocol.ValueType
import ee.schimke.livery.publisher.LiveryCommand
import ee.schimke.livery.publisher.TemplateSpec
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The UI Builder authoring flow: scaffold host inputs from a contract, design, export, import. */
class UiBuilderAuthoringTest {
  private val sample = File(requireNotNull(System.getProperty("livery.samples")) { "run through Gradle: gradle/sample-catalogs.gradle.kts" }).canonicalFile
  private val tile = File(sample, "designs/tile-default/in-app.rc").readBytes()
  private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
    createContext("/api/ui-builder/v1/designs/kitchen-tile/export.rc") { ex ->
      ex.responseHeaders.add("X-UI-Builder-Revision", "42")
      ex.sendResponseHeaders(200, tile.size.toLong()); ex.responseBody.use { it.write(tile) }
    }
    start()
  }
  private val url = "http://127.0.0.1:${server.address.port}"

  @AfterTest fun stop() = server.stop(0)

  @Test fun hostInputsAreTheContractsNamesAndDefaults() = runBlocking<Unit> {
    val contract = Contract(
      "demo/list", 1,
      inputs = mapOf(
        "title" to InputSpec(ValueType.String, default = JsonPrimitive("Hi")),
        "on" to InputSpec(ValueType.Bool, default = JsonPrimitive(true)),
        "at" to InputSpec(ValueType.Long),
        "tint" to InputSpec(ValueType.Color),
        "theme.primary" to InputSpec(ValueType.Color, role = "primary"),
      ),
      lists = mapOf("rows" to ListSpec(mapOf("label" to InputSpec(ValueType.String), "pct" to InputSpec(ValueType.Float)), max = 4)),
    )
    val r = UiBuilderScaffold.hostInputs(contract, items = 2)
    assertEquals(listOf("at", "on", "title", "rows.count", "rows.0.label", "rows.0.pct", "rows.1.label", "rows.1.pct"), r.stateVariables.keys.toList())
    assertEquals(listOf("theme.primary (theme role)", "tint (color)"), r.skipped.sorted())
    val title = r.stateVariables["title"]!!.jsonObject
    assertEquals("host", title["persistence"]!!.jsonPrimitive.content)
    assertEquals("text", title["type"]!!.jsonPrimitive.content)
    assertEquals("Hi", title["initialValue"]!!.jsonPrimitive.content)
    assertEquals("int", r.stateVariables["at"]!!.jsonObject["valueType"]!!.jsonPrimitive.content)
    assertEquals(true, r.stateVariables["on"]!!.jsonObject["initialValue"]!!.jsonPrimitive.content.toBooleanStrict())
    assertEquals(4 * 2 + 1 + 3, UiBuilderScaffold.hostInputs(contract).stateVariables.size, "every item by default")
  }

  @Test fun theMcpToolScaffoldsACatalogContract() = runBlocking<Unit> {
    val mcp = LiveryMcp.local(sample, extensions = listOf(UiBuilderExtension(UiBuilderClient(url, "t"))))
    val r = mcp.call(UiBuilderExtension.SCAFFOLD, JsonObject(mapOf("contract" to JsonPrimitive("live/status-tile@1"))))
    val body = Json.parseToJsonElement(r["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content).jsonObject
    assertEquals(setOf("available", "is_on", "level", "name", "state"), body["stateVariables"]!!.jsonObject.keys)
  }

  @Test fun importsAnExportIntoTheCatalogWithItsSource() = runBlocking<Unit> {
    val src = Files.createTempDirectory("catalog").toFile().also { sample.copyRecursively(it) }
    val cmd = LiveryCommand.installed().single { it.name == "ui-builder" }
    val target = """{"surface":"in-app","rcApiLevel":7}"""
    val code = cmd.run(listOf("import", "kitchen-tile", src.path, "--as", "tile-retro/kitchen", "--target", target, "--api", url), mapOf("UI_BUILDER_TOKEN" to "t"))
    assertEquals(0, code)
    val spec = Json.decodeFromString(TemplateSpec.serializer(), File(src, "designs/tile-retro/kitchen.json").readText())
    assertEquals(mapOf("tool" to "ui-builder", "design" to "kitchen-tile", "revision" to "42", "url" to "$url/api/ui-builder/v1/designs/kitchen-tile"), spec.source)
    assertEquals(listOf("is_on", "state"), spec.reads)
    assertTrue(File(src, "designs/tile-retro/kitchen.rc").readBytes().contentEquals(tile))

    // The same export is refused for a design whose contract it doesn't fit, and nothing is written.
    val refused = cmd.run(listOf("import", "kitchen-tile", src.path, "--as", "banner-bold/kitchen", "--target", target, "--api", url), mapOf("UI_BUILDER_TOKEN" to "t"))
    assertEquals(2, refused)
    assertTrue(!File(src, "designs/banner-bold/kitchen.rc").exists())
  }
}
