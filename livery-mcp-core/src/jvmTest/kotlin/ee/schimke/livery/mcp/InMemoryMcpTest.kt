package ee.schimke.livery.mcp

import java.io.File
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * The server with no platform host at all, over a catalog in a FakeFileSystem: what a browser or
 * an IDE plugin gets. Reads, drafts and previews work; renders and history say this host can't.
 */
class InMemoryMcpTest {
  private val fs = FakeFileSystem()
  private val sample = File(System.getProperty("user.dir")).resolve("../samples/catalog").canonicalFile
  private val root = "/catalog".toPath().also { root ->
    sample.walkTopDown().filter { it.isFile }.forEach { f ->
      val p = root / f.relativeTo(sample).invariantSeparatorsPath
      fs.createDirectories(p.parent!!)
      fs.write(p) { write(f.readBytes()) }
    }
  }
  private val mcp = LiveryMcp(SourceTree(fs, root), writable = true, previewOut = "/previews".toPath())

  private fun call(name: String, args: String = "{}") = mcp.call(name, Json.parseToJsonElement(args).jsonObject)
  private fun JsonObject.isError() = this["isError"]?.jsonPrimitive?.content == "true"
  private fun JsonObject.text() = this["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content

  @Test fun readsTheTree() {
    assertTrue("promo/banner@1" in call("list_contracts").text())
    assertTrue("tile-default" in call("list_designs", """{"contract":"live/status-tile@1"}""").text())
    val contract = Json.parseToJsonElement(call("get_contract", """{"contract":"promo/banner@1"}""").text()).jsonObject
    val example = contract["examples"]!!.jsonObject.values.first()
    val bound = Json.parseToJsonElement(call("validate_data", """{"contract":"promo/banner@1","data":$example}""").text()).jsonObject
    assertEquals(JsonPrimitive(true), bound["ok"], bound.toString())
  }

  @Test fun writesDraftsAndPreviewsIntoTheFileSystem() {
    val tile = fs.read(root / "designs/tile-default/in-app.rc") { readByteArray() }
    val sidecar = """{"target":{"surface":"in-app","rcApiLevel":8,"profiles":["androidx"]},"reads":["state","is_on"],"emits":["toggle"]}"""
    val draft = call("submit_draft", """{"design":"tile-retro","template":"in-app-v8","rc_base64":"${Base64.Default.encode(tile)}","sidecar":$sidecar}""")
    assertTrue("\"ok\":true" in draft.text().replace(" ", "").replace("\n", ""), draft.text())
    assertTrue(fs.exists(root / "designs/tile-retro/in-app-v8.rc"))

    assertTrue(!call("build_preview", """{"channel":"pr-1"}""").isError())
    assertTrue(fs.exists("/previews/pr-1/manifest.json".toPath()))
  }

  @Test fun saysWhatThisHostCannotDo() {
    val render = call("render_preview", """{"design":"tile-default","template":"in-app"}""")
    assertTrue(render.isError() && "can't render" in render.text(), render.text())
    val history = call("list_history", """{"design":"tile-default"}""")
    assertTrue(history.isError() && "git" in history.text(), history.text())
    val head = call("check_compatibility", """{"against":"HEAD"}""")
    assertTrue(head.isError(), head.toString().take(400))
    assertTrue(!call("check_compatibility", """{"against":"/catalog"}""").isError(), "another tree on the same file system")
  }
}
