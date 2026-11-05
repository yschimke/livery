package ee.schimke.livery.mcp

import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The tools against a copy of the sample catalog, whose tile-default in-app template is a real document. */
class LiveryMcpTest {
  private val src: File = Files.createTempDirectory("catalog").toFile().also {
    File("../samples/catalog").copyRecursively(it)
  }
  private val tile = File(src, "designs/tile-default/in-app.rc").readBytes()
  private val tileB64 = Base64.getEncoder().encodeToString(tile)
  private val out = Files.createTempDirectory("preview").toFile()
  private val mcp = LiveryMcp(SourceTree(src), writable = true, previewOut = out)

  private fun args(vararg pairs: Pair<String, Any>) = Json.parseToJsonElement(
    Json.encodeToString(JsonObject.serializer(), JsonObject(pairs.associate { (k, v) ->
      k to when (v) { is String -> JsonPrimitive(v); is Int -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); is JsonObject -> v; else -> error("$v") }
    }))
  ).jsonObject

  private fun call(name: String, vararg a: Pair<String, Any>) = mcp.call(name, args(*a))
  private fun JsonObject.isError() = this["isError"]?.jsonPrimitive?.content == "true"
  private fun JsonObject.text() = this["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
  private fun JsonObject.body() = Json.parseToJsonElement(text())

  @Test fun speaksMcp() {
    val init = mcp.handle(buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", "initialize") })!!
    assertEquals(LiveryMcp.PROTOCOL_VERSION, init["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
    assertNull(mcp.handle(buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") }))
    val tools = mcp.handle(buildJsonObject { put("jsonrpc", "2.0"); put("id", 2); put("method", "tools/list") })!!["result"]!!
      .jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
    assertTrue("submit_draft" in tools && "build_preview" in tools)
    val readOnly = LiveryMcp(SourceTree(src)).handle(buildJsonObject { put("jsonrpc", "2.0"); put("id", 3); put("method", "tools/list") })!!
    assertFalse("submit_draft" in readOnly.toString(), "a read-only server offers no writes")
    assertTrue(LiveryMcp(SourceTree(src)).call("submit_draft", JsonObject(emptyMap())).isError())
  }

  @Test fun contractsAndDesigns() {
    val contracts = (call("list_contracts").body() as JsonArray).map { it.jsonObject["contract"]!!.jsonPrimitive.content }
    assertTrue("promo/banner@1" in contracts && "live/status-tile@1" in contracts)
    assertTrue(call("get_contract", "contract" to "promo/banner@1").text().contains("\"headline\""))
    assertTrue(call("get_contract", "contract" to "nope@1").isError())
    val designs = call("list_designs", "contract" to "live/status-tile@1").text()
    assertTrue("tile-default" in designs && "banner-classic" !in designs)
  }

  @Test fun dataIsBoundOrRefused() {
    val ok = call("validate_data", "contract" to "promo/banner@1", "data" to args("headline" to "Hi", "cta" to "Go")).body().jsonObject
    assertEquals("true", ok["ok"]!!.jsonPrimitive.content)
    val bad = call("validate_data", "contract" to "promo/banner@1", "data" to args("headline" to "x".repeat(41))).body().jsonObject
    assertEquals("false", bad["ok"]!!.jsonPrimitive.content)
    assertTrue(bad["problems"].toString().contains("headline") && bad["problems"].toString().contains("cta"))
  }

  @Test fun theDocumentIsReadNotItsSidecar() {
    val v = call("validate_template", "contract" to "live/status-tile@1", "rc_base64" to tileB64).body().jsonObject
    assertEquals("true", v["ok"]!!.jsonPrimitive.content)
    assertEquals(listOf("is_on", "state"), v["reads"]!!.jsonArray.map { it.jsonPrimitive.content })
    assertEquals(listOf("toggle"), v["emits"]!!.jsonArray.map { it.jsonPrimitive.content })
    val wrong = call("validate_template", "contract" to "promo/banner@1", "rc_base64" to tileB64).body().jsonObject
    assertEquals("false", wrong["ok"]!!.jsonPrimitive.content)
    assertTrue(call("validate_template", "contract" to "promo/banner@1", "rc_base64" to "bm90IHJj").text().contains("not a Remote Compose"))
  }

  @Test fun rendersAPreview() {
    val r = call("render_preview", "design" to "tile-default", "template" to "in-app", "example" to "on", "width_dp" to 200, "height_dp" to 100)
    assertFalse(r.isError(), r.toString())
    val image = r["content"]!!.jsonArray.first().jsonObject
    assertEquals("image/png", image["mimeType"]!!.jsonPrimitive.content)
    val png = ImageIO.read(Base64.getDecoder().decode(image["data"]!!.jsonPrimitive.content).inputStream())
    assertEquals(400, png.width)
    assertTrue(call("render_preview", "design" to "tile-default", "template" to "in-app", "data" to args("state" to "On")).isError(), "data that doesn't bind isn't rendered")
  }

  @Test fun draftsPassTheGateOrAreNotWritten() {
    val sidecar = args("target" to args("surface" to "in-app", "rcApiLevel" to 7), "reads" to JsonObject(emptyMap()))
    val good = Json.parseToJsonElement("""{"target":{"surface":"in-app","rcApiLevel":8,"profiles":["androidx"]},"reads":["state","is_on"],"emits":["toggle"]}""").jsonObject
    val ok = call("submit_draft", "design" to "tile-retro", "template" to "in-app-v8", "rc_base64" to tileB64, "sidecar" to good).body().jsonObject
    assertEquals("true", ok["ok"]!!.jsonPrimitive.content, ok.toString())
    assertTrue(File(src, "designs/tile-retro/in-app-v8.rc").isFile)

    val undeclared = Json.parseToJsonElement("""{"target":{"surface":"in-app","rcApiLevel":9,"profiles":["androidx"]},"reads":["state"],"emits":[]}""").jsonObject
    val refused = call("submit_draft", "design" to "tile-retro", "template" to "in-app-v9", "rc_base64" to tileB64, "sidecar" to undeclared).body().jsonObject
    assertEquals("false", refused["ok"]!!.jsonPrimitive.content)
    assertTrue(refused["problems"].toString().contains("is_on"))
    assertFalse(File(src, "designs/tile-retro/in-app-v9.rc").exists(), "a failed draft is not written")

    // Passes its own inspection but breaks the catalog (a second template for one target): rolled back.
    val clash = Json.parseToJsonElement("""{"target":{"surface":"in-app","rcApiLevel":7,"profiles":["androidx"]},"reads":["state","is_on"],"emits":["toggle"]}""").jsonObject
    val before = File(src, "designs/tile-default/in-app.json").readText()
    val clashed = call("submit_draft", "design" to "tile-default", "template" to "in-app-copy", "rc_base64" to tileB64, "sidecar" to clash).body().jsonObject
    assertEquals("false", clashed["ok"]!!.jsonPrimitive.content)
    assertFalse(File(src, "designs/tile-default/in-app-copy.rc").exists())
    assertEquals(before, File(src, "designs/tile-default/in-app.json").readText())

    assertTrue(call("submit_draft", "design" to "../escape", "template" to "x", "rc_base64" to tileB64, "sidecar" to sidecar).isError())
    assertTrue(call("submit_draft", "design" to "brand-new", "template" to "in-app", "rc_base64" to tileB64, "sidecar" to good).isError(), "a new design needs its design.json")
  }

  @Test fun previewsAreUnsignedAndNeverTheProductionChannel() {
    assertTrue(call("build_preview", "channel" to "main").isError())
    val ok = call("build_preview", "channel" to "pr-12").body().jsonObject
    assertEquals("false", ok["signed"]!!.jsonPrimitive.content)
    val manifest = File(out, "pr-12/manifest.json").readText()
    assertTrue("\"channel\": \"pr-12\"" in manifest)
    assertFalse(File(out, "pr-12/manifest.sig.json").exists())
  }
}
