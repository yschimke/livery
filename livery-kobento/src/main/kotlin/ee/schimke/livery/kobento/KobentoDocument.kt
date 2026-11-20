package ee.schimke.livery.kobento

import ee.schimke.composeai.remotecompose.json.RemoteComposeJson
import ee.schimke.composeai.remotecompose.json.RemoteComposeJsonException
import ee.schimke.livery.publisher.CatalogProblems
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A Kobento document as `.rc` bytes. Kobento hands out three kinds:
 *
 * - [Kind.Rc]: a compiled document (Kobento's `json2rc`, its MCP `convert`, or a file from a
 *   device), used as it is;
 * - [Kind.AuthoringJson]: AndroidX's Remote Compose authoring JSON, the dialect Kobento's
 *   `json2rc` compiles byte-identically to Android's parser, compiled here with remotecompose-json;
 * - [Kind.ComponentJson]: authoring JSON that calls Kobento's component library
 *   (`"componentSets": ["lib:…"]`). That library is Kobento's own and not public, so only Kobento
 *   compiles it: [compile] refuses it and says to `convert` it with Kobento's MCP and import the `.rc`.
 */
object KobentoDocument {
  enum class Kind { Rc, AuthoringJson, ComponentJson }

  /** The top-level key that names Kobento component libraries in its JSON. */
  const val COMPONENT_SETS: String = "componentSets"

  fun kindOf(bytes: ByteArray): Kind {
    val first = bytes.firstOrNull { !it.toInt().toChar().isWhitespace() } ?: return Kind.Rc
    if (first.toInt().toChar() != '{') return Kind.Rc
    val root = runCatching { Json.parseToJsonElement(bytes.decodeToString()) }.getOrNull() as? JsonObject ?: return Kind.AuthoringJson
    return if (componentSets(root).isNotEmpty()) Kind.ComponentJson else Kind.AuthoringJson
  }

  /** The document as `.rc` bytes, or [CatalogProblems] saying why it can't be compiled here. */
  fun compile(bytes: ByteArray): ByteArray = when (kindOf(bytes)) {
    Kind.Rc -> bytes
    Kind.ComponentJson -> {
      val sets = componentSets(Json.parseToJsonElement(bytes.decodeToString()) as JsonObject)
      throw CatalogProblems(listOf(
        "uses Kobento components ($COMPONENT_SETS ${sets}): their library is Kobento's, so only Kobento compiles it. " +
          "Compile it with Kobento's MCP `convert` (or `build_kit`) and import the .rc it returns."
      ))
    }
    Kind.AuthoringJson -> try {
      RemoteComposeJson.compile(bytes.decodeToString())
    } catch (e: RemoteComposeJsonException) {
      throw CatalogProblems(listOf(e.message ?: "not Remote Compose authoring JSON"))
    }
  }

  private fun componentSets(root: JsonObject): List<String> = when (val v = root[COMPONENT_SETS]) {
    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content }
    is JsonPrimitive -> listOf(v.content)
    else -> emptyList()
  }
}
