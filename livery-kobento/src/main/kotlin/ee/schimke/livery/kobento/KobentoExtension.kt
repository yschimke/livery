@file:OptIn(ExperimentalEncodingApi::class)

package ee.schimke.livery.kobento

import ee.schimke.livery.mcp.LiveryMcp
import ee.schimke.livery.mcp.McpExtension
import ee.schimke.livery.protocol.Target
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Livery's side of a Kobento authoring session, next to Kobento's own MCP server (which an agent
 * runs alongside: its `build_kit`, `convert`, `render` and `audit`). No Kobento account is needed
 * here; nothing is sent anywhere.
 *
 * - `kobento_scaffold`: a catalog contract as the start of a Kobento document ([KobentoScaffold]).
 * - `kobento_compile`: a Kobento document (authoring JSON, or a `.rc` from Kobento's `convert` as
 *   base64) as `.rc` bytes, with what the target's player wouldn't play ([KobentoTargetCheck]). The
 *   agent then uses Livery's tools on `rc_base64`: `validate_template`, `render_preview`,
 *   `submit_draft` with `source` in the sidecar.
 */
class KobentoExtension : McpExtension {
  override val tools: List<JsonObject> = listOf(buildJsonObject {
    put("name", SCAFFOLD)
    put("description", "A catalog contract as the start of a Kobento (AndroidX authoring JSON) document: every value as an " +
      "exported USER: named value with an example's value, theme roles as USER:theme.<role> colours, and how to send its actions. " +
      "Lay it out (by hand, or with Kobento's build_kit) keeping the names, then kobento_compile it.")
    putJsonObject("inputSchema") {
      put("type", "object")
      putJsonObject("properties") {
        putJsonObject("contract") { put("type", "string"); put("description", "id@major") }
        putJsonObject("items") { put("type", "integer"); put("description", "Items per list; default each list's max") }
      }
      putJsonArray("required") { add("contract") }
      put("additionalProperties", false)
    }
  }, buildJsonObject {
    put("name", COMPILE)
    put("description", "Compiles a Kobento document (AndroidX authoring JSON, or a .rc from Kobento's convert) to Remote Compose " +
      "bytes and checks it plays on the target's player (no 3D unless the target names the '3d' profile). A document using " +
      "Kobento components (componentSets) must be converted with Kobento's MCP first. Then validate_template, render_preview, submit_draft.")
    putJsonObject("inputSchema") {
      put("type", "object")
      putJsonObject("properties") {
        putJsonObject("json") { put("type", "string"); put("description", "The authoring JSON document") }
        putJsonObject("rc_base64") { put("type", "string"); put("description", "Or: a compiled .rc, base64") }
        putJsonObject("target") { put("type", "object"); put("description", "The template's Target, e.g. {\"surface\":\"in-app\",\"rcApiLevel\":7}") }
      }
      putJsonArray("required") { add("target") }
      put("additionalProperties", false)
    }
  })

  override suspend fun call(name: String, args: JsonObject, server: LiveryMcp): JsonObject = try {
    when (name) {
      SCAFFOLD -> {
        val ref = (args["contract"] as? JsonPrimitive)?.contentOrNull ?: error("missing argument 'contract'")
        val contract = server.tree.contract(ref) ?: error("unknown contract $ref: ${server.tree.contracts().map { it.ref }}")
        val r = KobentoScaffold.authoringJson(contract, (args["items"] as? JsonPrimitive)?.longOrNull?.toInt())
        text(buildJsonObject {
          put("json", r.json)
          putJsonArray("notes") { r.notes.forEach { add(it) } }
          put("next", "lay the document out keeping every USER: name and action name, then kobento_compile it")
        }.toString())
      }
      else -> {
        val target = Json.decodeFromJsonElement(Target.serializer(), args["target"] ?: error("missing argument 'target'"))
        val json = (args["json"] as? JsonPrimitive)?.contentOrNull
        val rc = (args["rc_base64"] as? JsonPrimitive)?.contentOrNull
        val raw = json?.encodeToByteArray() ?: rc?.let { Base64.decode(it) } ?: error("give 'json' or 'rc_base64'")
        val bytes = KobentoDocument.compile(raw)
        val problems = KobentoTargetCheck.problems(bytes, target)
        text(buildJsonObject {
          if (problems.isEmpty()) { put("rc_base64", Base64.encode(bytes)); put("bytes", bytes.size) }
          putJsonArray("problems") { problems.forEach { add(it) } }
          putJsonObject("source") { put("tool", "kobento"); put("format", if (json != null) "json" else "rc") }
          put("next", if (problems.isEmpty()) "validate_template with this rc_base64, then render_preview; put source in the sidecar of submit_draft"
            else "fix the problems for this target, or target a player that plays them")
        }.toString(), error = problems.isNotEmpty())
      }
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    text(e.message ?: "$name failed", error = true)
  }

  private fun text(body: String, error: Boolean = false): JsonObject = buildJsonObject {
    putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", body) }) }
    if (error) put("isError", true)
  }

  /** On with `--kobento`: local tools only, no account. */
  class Factory : McpExtension.Factory {
    override fun create(args: List<String>, env: Map<String, String>): McpExtension? =
      if ("--kobento" in args) KobentoExtension() else null
  }

  companion object {
    const val SCAFFOLD: String = "kobento_scaffold"
    const val COMPILE: String = "kobento_compile"
  }
}
