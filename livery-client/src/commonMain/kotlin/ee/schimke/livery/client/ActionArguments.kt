package ee.schimke.livery.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * PROTOCOL.md §7: a host action's value as arguments. Nothing is no arguments; a JSON object of
 * scalars is the arguments; any other text, or a number, is the single argument `value`. Null for
 * anything else (malformed JSON, nested values): such a value is not an action.
 */
object ActionArguments {
  fun parse(text: String?): Map<String, String>? {
    if (text == null || text.isBlank()) return emptyMap()
    if (!text.trimStart().startsWith("{")) return mapOf("value" to text)
    val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    return obj.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: return null }
  }
}
