package ee.schimke.livery.mcp

import kotlinx.coroutines.runBlocking
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

/** The tools against a copy of the sample catalog, whose tile-default, banner-classic and poll-card in-app templates are real documents. */
class LiveryMcpTest {
  private val src: File = Files.createTempDirectory("catalog").toFile().also {
    File(requireNotNull(System.getProperty("livery.samples")) { "run through Gradle: gradle/sample-catalogs.gradle.kts" }).copyRecursively(it)
  }
  private val tile = File(src, "designs/tile-default/in-app.rc").readBytes()
  private val tileB64 = Base64.getEncoder().encodeToString(tile)
  private val out = Files.createTempDirectory("preview").toFile()
  private val mcp = LiveryMcp.local(src, writable = true, previewOut = out)

  private fun args(vararg pairs: Pair<String, Any>) = Json.parseToJsonElement(
    Json.encodeToString(JsonObject.serializer(), JsonObject(pairs.associate { (k, v) ->
      k to when (v) { is String -> JsonPrimitive(v); is Int -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); is JsonObject -> v; else -> error("$v") }
    }))
  ).jsonObject

  private suspend fun call(name: String, vararg a: Pair<String, Any>) = mcp.call(name, args(*a))
  private fun JsonObject.isError() = this["isError"]?.jsonPrimitive?.content == "true"
  private fun JsonObject.text() = this["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
  private fun JsonObject.body() = Json.parseToJsonElement(text())

  @Test fun speaksMcp() = runBlocking<Unit> {
    val init = mcp.handle(buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", "initialize") })!!
    assertEquals(LiveryMcp.PROTOCOL_VERSION, init["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
    assertNull(mcp.handle(buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") }))
    val tools = mcp.handle(buildJsonObject { put("jsonrpc", "2.0"); put("id", 2); put("method", "tools/list") })!!["result"]!!
      .jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
    assertTrue("submit_draft" in tools && "build_preview" in tools)
    val readOnly = LiveryMcp.local(src).handle(buildJsonObject { put("jsonrpc", "2.0"); put("id", 3); put("method", "tools/list") })!!
    assertFalse("submit_draft" in readOnly.toString(), "a read-only server offers no writes")
    assertTrue(LiveryMcp.local(src).call("submit_draft", JsonObject(emptyMap())).isError())
  }

  @Test fun contractsAndDesigns() = runBlocking<Unit> {
    val contracts = (call("list_contracts").body() as JsonArray).map { it.jsonObject["contract"]!!.jsonPrimitive.content }
    assertTrue("promo/banner@1" in contracts && "live/status-tile@1" in contracts)
    assertTrue(call("get_contract", "contract" to "promo/banner@1").text().contains("\"headline\""))
    assertTrue(call("get_contract", "contract" to "nope@1").isError())
    val designs = call("list_designs", "contract" to "live/status-tile@1").text()
    assertTrue("tile-default" in designs && "banner-classic" !in designs)
  }

  /** A linked template is listed at its document's size, as java.io.File reported it. */
  @Test fun linkedTemplatesReportTheirDocumentsSize() = runBlocking<Unit> {
    // The link replaces the template inside this test's own copy of the sample; its target is a copy too.
    val target = Files.createTempFile("tile", ".rc").toFile().apply { writeBytes(tile); deleteOnExit() }
    val rc = File(src, "designs/tile-default/in-app.rc").apply { delete() }
    Files.createSymbolicLink(rc.toPath(), target.toPath())
    val design = (call("list_designs", "contract" to "live/status-tile@1").body() as JsonArray)
      .map { it.jsonObject }.single { it["design"]!!.jsonObject["id"]!!.jsonPrimitive.content == "tile-default" }
    val inApp = design["templates"]!!.jsonArray.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == "in-app" }
    assertEquals(tile.size.toLong(), inApp["bytes"]!!.jsonPrimitive.content.toLong())
  }

  @Test fun a2uiComponentsAndMessages() = runBlocking<Unit> {
    val catalog = call("a2ui_component_schema", "catalog_id" to "https://x/v1").body().jsonObject
    assertTrue("PromoBanner" in catalog["components"]!!.jsonObject, catalog.toString())
    assertEquals("PromoBanner", call("a2ui_component_schema", "contract" to "promo/banner@1").body().jsonObject["component"]!!.jsonPrimitive.content)
    val message = Json.parseToJsonElement("""{"version":"v0.9","updateComponents":{"surfaceId":"s","components":[
      {"id":"promo","component":"PromoBanner","headline":{"path":"/t"},"cta":"Go","design":"banner-nope","actions":{"nope":{"event":{"name":"x"}}}}]}}""").jsonObject
    val bad = call("validate_a2ui", "message" to message, "data_model" to args("t" to "x".repeat(200))).body().jsonObject
    assertEquals("false", bad["ok"]!!.jsonPrimitive.content)
    val properties = bad["problems"]!!.jsonArray.map { it.jsonObject["property"]!!.jsonPrimitive.content }.toSet()
    assertEquals(setOf("design", "actions.nope", "headline"), properties, bad.toString())
    val good = Json.parseToJsonElement(message.toString().replace("banner-nope", "banner-bold").replace(""","actions":{"nope":{"event":{"name":"x"}}}""", "")).jsonObject
    assertEquals("true", call("validate_a2ui", "message" to good, "data_model" to args("t" to "Spring sale")).body().jsonObject["ok"]!!.jsonPrimitive.content)
    // A whole response, as an array of messages, and the schema that says it may be one.
    val both = buildJsonObject { put("message", JsonArray(listOf(good, message))); put("data_model", args("t" to "Spring sale")) }
    assertEquals("false", mcp.call("validate_a2ui", both).body().jsonObject["ok"]!!.jsonPrimitive.content)
    val tools = mcp.handle(buildJsonObject { put("jsonrpc", "2.0"); put("id", 4); put("method", "tools/list") })!!["result"]!!
      .jsonObject["tools"]!!.jsonArray.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == "validate_a2ui" }
    val type = tools["inputSchema"]!!.jsonObject["properties"]!!.jsonObject["message"]!!.jsonObject["type"]!!.jsonArray
    assertEquals(listOf("object", "array"), type.map { it.jsonPrimitive.content })
  }

  @Test fun dataIsBoundOrRefused() = runBlocking<Unit> {
    val ok = call("validate_data", "contract" to "promo/banner@1", "data" to args("headline" to "Hi", "cta" to "Go")).body().jsonObject
    assertEquals("true", ok["ok"]!!.jsonPrimitive.content)
    val bad = call("validate_data", "contract" to "promo/banner@1", "data" to args("headline" to "x".repeat(41))).body().jsonObject
    assertEquals("false", bad["ok"]!!.jsonPrimitive.content)
    assertTrue(bad["problems"].toString().contains("headline") && bad["problems"].toString().contains("cta"))
  }

  @Test fun theDocumentIsReadNotItsSidecar() = runBlocking<Unit> {
    val v = call("validate_template", "contract" to "live/status-tile@1", "rc_base64" to tileB64).body().jsonObject
    assertEquals("true", v["ok"]!!.jsonPrimitive.content)
    assertEquals(listOf("is_on", "state"), v["reads"]!!.jsonArray.map { it.jsonPrimitive.content })
    assertEquals(listOf("toggle"), v["emits"]!!.jsonArray.map { it.jsonPrimitive.content })
    val wrong = call("validate_template", "contract" to "promo/banner@1", "rc_base64" to tileB64).body().jsonObject
    assertEquals("false", wrong["ok"]!!.jsonPrimitive.content)
    assertTrue(call("validate_template", "contract" to "promo/banner@1", "rc_base64" to "bm90IHJj").text().contains("not a Remote Compose"))
  }

  @Test fun rendersAPreview() = runBlocking<Unit> {
    val r = call("render_preview", "design" to "tile-default", "template" to "in-app", "example" to "on", "width_dp" to 200, "height_dp" to 100)
    assertFalse(r.isError(), r.toString())
    val image = r["content"]!!.jsonArray.first().jsonObject
    assertEquals("image/png", image["mimeType"]!!.jsonPrimitive.content)
    val png = ImageIO.read(Base64.getDecoder().decode(image["data"]!!.jsonPrimitive.content).inputStream())
    assertEquals(400, png.width)
    assertTrue(call("render_preview", "design" to "tile-default", "template" to "in-app", "data" to args("state" to "On")).isError(), "data that doesn't bind isn't rendered")
  }

  @Test fun draftsPassTheGateOrAreNotWritten() = runBlocking<Unit> {
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

  @Test fun previewsAreUnsignedAndNeverTheProductionChannel() = runBlocking<Unit> {
    assertTrue(call("build_preview", "channel" to "main").isError())
    val ok = call("build_preview", "channel" to "pr-12").body().jsonObject
    assertEquals("false", ok["signed"]!!.jsonPrimitive.content)
    val manifest = File(out, "pr-12/manifest.json").readText()
    assertTrue("\"channel\": \"pr-12\"" in manifest)
    assertFalse(File(out, "pr-12/manifest.sig.json").exists())
  }

  @Test fun publishesOneDocumentAsAPreview() = runBlocking<Unit> {
    val r = call("publish_preview", "contract" to "live/status-tile@1", "rc_base64" to tileB64, "source" to args("tool" to "agent")).body().jsonObject
    assertEquals("true", r["ok"]!!.jsonPrimitive.content, r.toString())
    val channel = r["channel"]!!.jsonPrimitive.content
    val manifest = File(out, "$channel/manifest.json").readText()
    assertTrue("\"channel\": \"$channel\"" in manifest && "\"tool\": \"agent\"" in manifest, manifest)
    assertTrue(call("publish_preview", "contract" to "promo/banner@1", "rc_base64" to tileB64).isError(), "checked against its contract")
  }

  @Test fun historyFromGit() = runBlocking<Unit> {
    fun git(vararg a: String) = ProcessBuilder(listOf("git", "-C", src.path, "-c", "user.name=t", "-c", "user.email=t@t") + a)
      .redirectErrorStream(true).start().also { it.waitFor() }.inputStream.readBytes().decodeToString()
    git("init", "-q"); git("add", "."); git("commit", "-qm", "first tile")
    val first = git("rev-parse", "HEAD").trim()
    File(src, "designs/tile-default/in-app.rc").writeBytes(tile + byteArrayOf()) // same bytes: no change
    File(src, "designs/tile-default/in-app.json").appendText("\n"); git("commit", "-qam", "touch sidecar")
    File(src, "designs/tile-default/in-app.rc").writeBytes(byteArrayOf(1, 2, 3)); git("commit", "-qam", "break the tile")

    val h = call("list_history", "design" to "tile-default", "template" to "in-app").body().jsonArray.single().jsonObject
    val subjects = h["changes"]!!.jsonArray.map { it.jsonObject["subject"]!!.jsonPrimitive.content }
    assertEquals(listOf("break the tile", "first tile"), subjects, "the commits that changed the document")
    assertTrue(call("render_preview", "design" to "tile-default", "template" to "in-app").isError(), "today's bytes don't render")
    val old = call("render_preview", "design" to "tile-default", "template" to "in-app", "revision" to first, "example" to "on")
    assertFalse(old.isError(), old.toString())
    assertTrue(call("render_preview", "design" to "tile-default", "template" to "in-app", "revision" to "--output=/tmp/x").isError())
  }

  @Test fun badArgumentsAreToolErrorsNotACrash() = runBlocking<Unit> {
    for (bad in listOf("width_dp" to 0, "height_dp" to 4097, "density" to 5)) {
      assertTrue(call("render_preview", "design" to "tile-default", "template" to "in-app", bad).isError(), "$bad")
    }
  }

  @Test fun anInternalErrorNamesTheExceptionWhenItHasNoMessage() = runBlocking<Unit> {
    val request = Json.parseToJsonElement("""{"jsonrpc":"2.0","id":7,"method":"tools/call"}""").jsonObject
    val error = internalError(request, ExceptionInInitializerError())!!["error"]!!.jsonObject
    assertEquals("ExceptionInInitializerError", error["message"]!!.jsonPrimitive.content)
    val withMessage = internalError(request, IllegalStateException("boom"))!!["error"]!!.jsonObject
    assertEquals("IllegalStateException: boom", withMessage["message"]!!.jsonPrimitive.content)
    assertNull(internalError(Json.parseToJsonElement("""{"method":"x"}""").jsonObject, IllegalStateException()))
  }

  @Test fun aDraftCannotReplaceTheDesignsMetadata() = runBlocking<Unit> {
    val good = Json.parseToJsonElement("""{"target":{"surface":"in-app","rcApiLevel":8},"reads":["state","is_on"],"emits":["toggle"]}""").jsonObject
    val before = File(src, "designs/tile-retro/design.json").readText()
    assertTrue(call("submit_draft", "design" to "tile-retro", "template" to "design", "rc_base64" to tileB64, "sidecar" to good).isError())
    assertEquals(before, File(src, "designs/tile-retro/design.json").readText())
  }

  @Test fun hostWrittenNamesAreNotInTheSuggestedSidecar() = runBlocking<Unit> {
    // The tile with one of its values renamed to a Wear container name, which the host writes.
    val doc = ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec.decode(tile, ee.schimke.composeai.rcplayer.protocol.RcWireLimits())
    val wear = doc.copy(operations = doc.operations.map { op ->
      if (op is ee.schimke.composeai.rcplayer.protocol.RcNamedVariable && op.name == "USER:is_on") op.copy(name = "USER:wear.container.corner_radius") else op
    })
    val b64 = Base64.getEncoder().encodeToString(ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec.encode(wear))
    val v = call("validate_template", "contract" to "live/status-tile@1", "rc_base64" to b64).body().jsonObject
    assertEquals("true", v["ok"]!!.jsonPrimitive.content)
    assertEquals(listOf("state"), v["sidecar"]!!.jsonObject["reads"]!!.jsonArray.map { it.jsonPrimitive.content })
    assertFalse(call("publish_preview", "contract" to "live/status-tile@1", "rc_base64" to b64).isError(), "and a preview of it builds")
  }

  @Test fun historyWhenTheCatalogIsASubdirectoryOfTheCheckout() = runBlocking<Unit> {
    val repo = Files.createTempDirectory("repo").toFile()
    val catalog = File(repo, "catalogs/main").also { File(requireNotNull(System.getProperty("livery.samples")) { "run through Gradle: gradle/sample-catalogs.gradle.kts" }).copyRecursively(it) }
    fun git(vararg a: String) = ProcessBuilder(listOf("git", "-C", repo.path, "-c", "user.name=t", "-c", "user.email=t@t") + a)
      .redirectErrorStream(true).start().also { it.waitFor() }.inputStream.readBytes().decodeToString()
    git("init", "-q"); git("add", "."); git("commit", "-qm", "add catalog")
    val first = git("rev-parse", "HEAD").trim()
    val sub = LiveryMcp.local(catalog)
    val h = Json.parseToJsonElement(sub.call("list_history", args("design" to "tile-default", "template" to "in-app")).text()).jsonArray.single().jsonObject
    assertEquals(listOf("add catalog"), h["changes"]!!.jsonArray.map { it.jsonObject["subject"]!!.jsonPrimitive.content })
    assertFalse(sub.call("render_preview", args("design" to "tile-default", "template" to "in-app", "revision" to first)).isError())
  }

  @Test fun anOlderRevisionRendersUnderTheNameItHadThen() = runBlocking<Unit> {
    fun git(vararg a: String) = ProcessBuilder(listOf("git", "-C", src.path, "-c", "user.name=t", "-c", "user.email=t@t") + a)
      .redirectErrorStream(true).start().also { it.waitFor() }.inputStream.readBytes().decodeToString()
    val dir = File(src, "designs/tile-default")
    File(dir, "in-app.rc").renameTo(File(dir, "phone.rc")); File(dir, "in-app.json").renameTo(File(dir, "phone.json"))
    git("init", "-q"); git("add", "."); git("commit", "-qm", "as phone")
    val first = git("rev-parse", "HEAD").trim()
    git("mv", "designs/tile-default/phone.rc", "designs/tile-default/in-app.rc"); git("mv", "designs/tile-default/phone.json", "designs/tile-default/in-app.json")
    git("commit", "-qm", "rename to in-app")
    val h = call("list_history", "design" to "tile-default", "template" to "in-app").body().jsonArray.single().jsonObject
    assertTrue(first in h["changes"].toString(), "history follows the rename")
    val r = call("render_preview", "design" to "tile-default", "template" to "in-app", "revision" to first)
    assertFalse(r.isError(), r.toString())
  }

  @Test fun checksCompatibilityAgainstHead() = runBlocking<Unit> {
    fun git(vararg a: String) = ProcessBuilder(listOf("git", "-C", src.path, "-c", "user.name=t", "-c", "user.email=t@t") + a)
      .redirectErrorStream(true).start().also { it.waitFor() }.inputStream.readBytes().decodeToString()
    git("init", "-q"); git("add", "."); git("commit", "-qm", "catalog")
    val same = call("check_compatibility").body().jsonObject
    assertEquals("true", same["ok"]!!.jsonPrimitive.content, same.toString())

    val contract = File(src, "contracts/status-tile.json")
    contract.writeText(contract.readText().replace("\"maxLength\": 40", "\"maxLength\": 20"))
    val broken = call("check_compatibility", "against" to "HEAD").body().jsonObject
    assertEquals("false", broken["ok"]!!.jsonPrimitive.content)
    assertTrue("maxLength tightened" in broken["problems"].toString(), broken.toString())
    assertTrue(call("check_compatibility", "against" to "--output=/tmp/x").isError(), "an option is never a revision")

    val v = call("validate_template", "contract" to "live/status-tile@1", "rc_base64" to tileB64).body().jsonObject
    assertEquals(0, v["requiresMinor"]!!.jsonPrimitive.content.toInt())
  }
}
