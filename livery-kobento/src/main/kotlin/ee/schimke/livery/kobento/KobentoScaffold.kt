package ee.schimke.livery.kobento

import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.InputSpec
import ee.schimke.livery.protocol.ValueType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A contract as the start of a Kobento document: AndroidX authoring JSON (what Kobento's `json2rc`
 * and remotecompose-json compile) that declares every value the contract can bind as an exported
 * `USER:` named value (PROTOCOL.md §4.2) with an example's value, theme roles as `USER:theme.<role>`
 * colours, and one plain column that shows the strings and sends the first argument-free action.
 * It compiles, and imports into the catalog as it is; a person, an agent or Kobento's `build_kit`
 * then lays it out, keeping the names. [Result.notes] says what it left out and how actions are sent.
 *
 * A list's items are `<list>.<i>.<field>` for `i` below [items] (at most the list's `max`), with
 * `<list>.count`. A bool is an int (0 or 1), as Livery binds it.
 */
object KobentoScaffold {
  data class Result(val json: JsonObject, val notes: List<String>)

  fun authoringJson(contract: Contract, items: Int? = null, width: Int = 320, height: Int = 160): Result {
    val notes = mutableListOf<String>()
    val example = contract.examples.entries.minByOrNull { it.key }?.value ?: JsonObject(emptyMap())
    val strings = mutableListOf<String>()
    val variables = buildJsonArray {
      fun add(name: String, spec: InputSpec, sample: JsonElement?) {
        val value = sample ?: spec.default
        val (vtype, v) = when (spec.type) {
          ValueType.String -> { strings += name; "string" to JsonPrimitive((value as? JsonPrimitive)?.content?.ifEmpty { null } ?: name) }
          ValueType.Bool -> "int" to JsonPrimitive(if ((value as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true) 1 else 0)
          ValueType.Int, ValueType.Long -> "int" to JsonPrimitive((value as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: 0L)
          ValueType.Float -> "float" to JsonPrimitive((value as? JsonPrimitive)?.content?.toDoubleOrNull() ?: spec.min ?: 0.0)
          ValueType.Color -> "color" to JsonPrimitive(color(value))
          ValueType.Image -> {
            notes += "$name (image): draw it from the bitmap URL livery:value/$name, which the host fills"
            return
          }
        }
        addJsonObject {
          put("type", "variable"); put("name", "$USER$name"); put("vtype", vtype); put("value", v); put("export", true)
        }
      }
      for ((name, spec) in contract.inputs.entries.sortedBy { it.key }) add(name, spec, example[name])
      for ((list, spec) in contract.lists.entries.sortedBy { it.key }) {
        val n = (items ?: spec.max).coerceIn(0, spec.max)
        add("$list.count", InputSpec(ValueType.Int), JsonPrimitive(n))
        repeat(n) { i -> for ((field, f) in spec.item.entries.sortedBy { it.key }) add("$list.$i.$field", f, null) }
      }
    }
    val tap = contract.actions.entries.sortedBy { it.key }.firstOrNull { (_, a) -> a.args.values.none { it.required } }?.key
    for ((name, a) in contract.actions.entries.sortedBy { it.key }) {
      notes += if (a.args.isEmpty()) "action $name: {\"type\": \"hostAction\", \"name\": \"$name\"} in an onClick"
      else "action $name: {\"type\": \"hostAction\", \"name\": \"$name\", \"value\": <arg>} carrying its arguments ${a.args.keys.sorted()}"
    }
    val primary = contract.inputs.entries.firstOrNull { it.value.role == "primary" || it.key == "theme.primary" }?.key
    val json = buildJsonObject {
      putJsonObject("header") { put("apiLevel", 7); put("width", width); put("height", height); put("contentDescription", contract.title ?: contract.ref) }
      put("root", buildJsonArray {
        variables.forEach { add(it) }
        addJsonObject {
          put("type", "column")
          putJsonArray("modifiers") {
            add("fillMaxSize")
            if (primary != null) addJsonObject { put("background", "@$USER$primary") }
            if (tap != null) addJsonObject { putJsonObject("onClick") { put("type", "hostAction"); put("name", tap) } }
          }
          putJsonArray("children") { strings.forEach { s -> addJsonObject { put("type", "text"); put("text", "@$USER$s") } } }
        }
      })
    }
    return Result(json, notes)
  }

  private const val USER = "USER:"

  private fun color(value: JsonElement?): String {
    val p = value as? JsonPrimitive ?: return "#FF808080"
    p.content.toLongOrNull()?.let { return "#" + (it and 0xFFFFFFFFL).toString(16).padStart(8, '0').uppercase() }
    return p.content.takeIf { it.startsWith("#") } ?: "#FF808080"
  }
}
