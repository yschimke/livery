package ee.schimke.livery.kobento

import ee.schimke.livery.mcp.LiveryMcp
import ee.schimke.livery.mcp.local
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.Target
import ee.schimke.livery.publisher.CatalogProblems
import ee.schimke.livery.publisher.LiveryCommand
import ee.schimke.livery.publisher.TemplateImport
import ee.schimke.livery.publisher.TemplateSpec
import ee.schimke.livery.publisher.contract
import ee.schimke.livery.publisher.into
import java.io.File
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A Kobento document becomes a catalog template: scaffold, lay out, compile, check, import. */
@OptIn(ExperimentalEncodingApi::class)
class KobentoImportTest {
  private val sample = File(requireNotNull(System.getProperty("livery.samples")) { "run through Gradle: gradle/sample-catalogs.gradle.kts" }).canonicalFile
  private val inApp = Target(Surfaces.IN_APP, 7)
  private val contract = TemplateImport.contract(sample, "live/status-tile@1")
  private fun tree(): File = Files.createTempDirectory("catalog").toFile().also { sample.copyRecursively(it) }

  /** A plain Kobento / AndroidX authoring document reading [reads] (`USER:` strings) and sending `toggle`. */
  private fun document(vararg reads: String) = """
    {"header": {"apiLevel": 7, "width": 200, "height": 80},
     "root": [
       ${reads.joinToString("") { """{"type": "variable", "name": "USER:$it", "vtype": "string", "value": "$it", "export": true},""" }}
       {"type": "column", "modifiers": ["fillMaxSize", {"onClick": {"type": "hostAction", "name": "toggle"}}],
        "children": [${reads.joinToString { """{"type": "text", "text": "@USER:$it"}""" }}]}
     ]}
  """.trimIndent()

  @Test fun theScaffoldCompilesAndImportsAsItIs() {
    val r = KobentoScaffold.authoringJson(contract)
    val bytes = KobentoDocument.compile(r.json.toString().encodeToByteArray())
    assertEquals(emptyList(), KobentoTargetCheck.problems(bytes, inApp))
    val src = tree()
    val i = TemplateImport.into(src, "tile-retro", "kobento", okio.ByteString.of(*bytes), inApp, mapOf("tool" to "kobento"))
    assertEquals(listOf("available", "is_on", "level", "name", "state", "theme.primary"), i.spec.reads)
    assertEquals(listOf("toggle"), i.spec.emits, "the first action without required arguments")
    assertTrue(r.notes.any { it.startsWith("action details:") && "route" in it }, r.notes.toString())
  }

  @Test fun theCommandImportsJsonWithItsSource() {
    val src = tree()
    val doc = File(Files.createTempDirectory("kobento").toFile(), "kitchen.json").apply { writeText(document("name", "state")) }
    val cmd = LiveryCommand.installed().single { it.name == "kobento" }
    val code = cmd.run(listOf("import", doc.path, src.path, "--as", "tile-retro/kitchen", "--target", """{"surface":"in-app","rcApiLevel":7}""",
      "--source", "page=https://mcp.kobento.dev/p/kitchen"), emptyMap())
    assertEquals(0, code)
    val spec = Json.decodeFromString(TemplateSpec.serializer(), File(src, "designs/tile-retro/kitchen.json").readText())
    assertEquals(listOf("name", "state"), spec.reads)
    assertEquals(mapOf("tool" to "kobento", "file" to "kitchen.json", "format" to "json", "page" to "https://mcp.kobento.dev/p/kitchen"), spec.source)
    assertTrue(File(src, "designs/tile-retro/kitchen.rc").isFile)
  }

  @Test fun aValueTheContractDoesntDeclareIsRefused() {
    val bytes = KobentoDocument.compile(document("name", "system.city").encodeToByteArray())
    val e = assertFailsWith<CatalogProblems> { TemplateImport.into(tree(), "tile-retro", "city", okio.ByteString.of(*bytes), inApp) }
    assertTrue("system.city" in e.message.orEmpty(), e.message)
  }

  @Test fun componentJsonGoesThroughKobentosConvert() {
    val json = """{"componentSets": ["lib:gauges"], "root": {"type": "lib:gauges/ring", "value": "@USER:level"}}"""
    assertEquals(KobentoDocument.Kind.ComponentJson, KobentoDocument.kindOf(json.encodeToByteArray()))
    val e = assertFailsWith<CatalogProblems> { KobentoDocument.compile(json.encodeToByteArray()) }
    assertTrue("convert" in e.message.orEmpty(), e.message)
  }

  @Test fun threeDIsRefusedOutsideA3dTarget() {
    // A document with one SET_TEXTURE_3D (opcode 118, an int bitmap id) after its operations.
    val bytes = KobentoDocument.compile(document("name").encodeToByteArray()) + byteArrayOf(118, 0, 0, 0, 0)
    val problems = KobentoTargetCheck.problems(bytes, inApp)
    assertTrue(problems.single().contains("3D"), problems.toString())
    val widget = KobentoTargetCheck.problems(bytes, Target(Surfaces.LAUNCHER_WIDGET, 7, profiles = listOf("widgets", KobentoTargetCheck.PROFILE_3D)))
    assertTrue(widget.any { "launcher-widget" in it }, widget.toString())
  }

  @Test fun aLevelThePlayerDoesntReadIsRefused() {
    val bytes = KobentoDocument.compile(document("name").encodeToByteArray())
    assertTrue(KobentoTargetCheck.problems(bytes, Target(Surfaces.IN_APP, 6)).isNotEmpty())
  }

  @Test fun theMcpToolsScaffoldAndCompile() = runBlocking<Unit> {
    val mcp = LiveryMcp.local(sample, extensions = listOf(KobentoExtension()))
    fun body(r: JsonObject) = Json.parseToJsonElement(r["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content).jsonObject
    val scaffold = body(mcp.call(KobentoExtension.SCAFFOLD, JsonObject(mapOf("contract" to JsonPrimitive("live/status-tile@1")))))
    val target = Json.parseToJsonElement("""{"surface":"in-app","rcApiLevel":7}""")
    val compiled = mcp.call(KobentoExtension.COMPILE, JsonObject(mapOf("json" to JsonPrimitive(scaffold["json"].toString()), "target" to target)))
    assertEquals(null, compiled["isError"], compiled.toString())
    val rc = Base64.decode(body(compiled)["rc_base64"]!!.jsonPrimitive.content)
    val validated = mcp.call("validate_template", JsonObject(mapOf("contract" to JsonPrimitive("live/status-tile@1"), "rc_base64" to JsonPrimitive(Base64.encode(rc)))))
    assertEquals(null, validated["isError"], validated.toString())
  }
}
