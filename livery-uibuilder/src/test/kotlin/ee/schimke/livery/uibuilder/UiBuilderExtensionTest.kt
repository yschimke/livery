package ee.schimke.livery.uibuilder

import com.sun.net.httpserver.HttpServer
import ee.schimke.livery.mcp.LiveryMcp
import ee.schimke.livery.mcp.McpExtension
import ee.schimke.livery.mcp.SourceTree
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class UiBuilderExtensionTest {
  private val tile = File("../samples/catalog/designs/tile-default/in-app.rc").readBytes()
  private val seen = mutableListOf<String>()
  private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
    createContext("/api/ui-builder/v1/designs/") { ex ->
      seen += ex.requestURI.toString()
      val ok = ex.requestURI.path.endsWith("/kitchen-tile/export.rc") && "token=secret" in ex.requestURI.query
      if (ok) ex.responseHeaders.add("X-UI-Builder-Revision", "42")
      val body = if (ok) tile else "no".encodeToByteArray()
      ex.sendResponseHeaders(if (ok) 200 else 404, body.size.toLong()); ex.responseBody.use { it.write(body) }
    }
    start()
  }
  private val url = "http://127.0.0.1:${server.address.port}"

  @AfterTest fun stop() = server.stop(0)

  private fun JsonObject.text() = this["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content

  @Test fun exportsADesignWithItsRevision() {
    val mcp = LiveryMcp(SourceTree(File("../samples/catalog")), extensions = listOf(UiBuilderExtension(UiBuilderClient(url, "secret"))))
    val r = mcp.call(UiBuilderExtension.TOOL, JsonObject(mapOf("ui_design" to JsonPrimitive("kitchen-tile"))))
    val body = Json.parseToJsonElement(r.text()).jsonObject
    assertEquals(tile.toList(), Base64.getDecoder().decode(body["rc_base64"]!!.jsonPrimitive.content).toList())
    val source = body["source"]!!.jsonObject
    assertEquals("ui-builder", source["tool"]!!.jsonPrimitive.content)
    assertEquals("42", source["revision"]!!.jsonPrimitive.content, "the revision UI Builder drew")

    // Its document goes through Livery's own gate like any other.
    val v = mcp.call("validate_template", JsonObject(mapOf("contract" to JsonPrimitive("live/status-tile@1"), "rc_base64" to body["rc_base64"]!!)))
    assertTrue("\"ok\": true" in v.text())
  }

  @Test fun aRevisionIsAskedForAndFailuresAreToolErrors() {
    val ext = UiBuilderExtension(UiBuilderClient(url, "secret"))
    ext.call(UiBuilderExtension.TOOL, JsonObject(mapOf("ui_design" to JsonPrimitive("kitchen-tile"), "revision" to JsonPrimitive(7))), LiveryMcp(SourceTree(File("."))))
    assertTrue(seen.last().endsWith("&revision=7"))
    val missing = ext.call(UiBuilderExtension.TOOL, JsonObject(mapOf("ui_design" to JsonPrimitive("nope"))), LiveryMcp(SourceTree(File("."))))
    assertEquals("true", missing["isError"]!!.jsonPrimitive.content)
    val bad = ext.call(UiBuilderExtension.TOOL, JsonObject(mapOf("ui_design" to JsonPrimitive("../x"))), LiveryMcp(SourceTree(File("."))))
    assertEquals("true", bad["isError"]!!.jsonPrimitive.content)
  }

  @Test fun onlyWhenConfigured() {
    assertNull(UiBuilderExtension.Factory().create(listOf("catalog"), emptyMap()))
    val loaded = McpExtension.load(listOf("catalog", "--ui-builder", url), mapOf("UI_BUILDER_TOKEN" to "secret"))
    assertEquals(listOf(UiBuilderExtension.TOOL, UiBuilderExtension.SCAFFOLD), loaded.flatMap { it.tools }.map { it["name"]!!.jsonPrimitive.content }, "found by ServiceLoader")
  }
}
