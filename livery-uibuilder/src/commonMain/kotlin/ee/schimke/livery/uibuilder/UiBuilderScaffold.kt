package ee.schimke.livery.uibuilder

import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.InputSpec
import ee.schimke.livery.protocol.ValueType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A contract as UI Builder host inputs: one `"persistence": "host"` state variable per value the
 * contract declares, under the name the host binds it by, with the contract's default as its
 * initial value. Pasted into a design's `stateVariables`, the design reads them, and its export
 * declares each as a named value, so it plays under Livery with no renaming.
 *
 * A list's items are `<list>.<i>.<field>` for `i` below [items] (at most the list's `max`), with
 * `<list>.count`. Colours and images aren't UI Builder host input types: they are listed in
 * [Result.skipped], and theme colours come from the host's theme anyway.
 */
object UiBuilderScaffold {
  data class Result(val stateVariables: JsonObject, val skipped: List<String>)

  fun hostInputs(contract: Contract, items: Int? = null): Result {
    val skipped = mutableListOf<String>()
    val vars = buildJsonObject {
      fun add(name: String, spec: InputSpec) {
        val valueType = when (spec.type) {
          ValueType.String -> "string"
          ValueType.Bool -> "bool"
          ValueType.Int, ValueType.Long -> "int"
          ValueType.Float -> "float"
          ValueType.Color, ValueType.Image -> { skipped += "$name (${spec.type.name.lowercase()})"; return }
        }
        put(name, buildJsonObject {
          put("type", if (valueType == "string") "text" else "value")
          put("valueType", valueType)
          put("initialValue", initial(valueType, spec.default))
          put("nullable", false)
          put("persistence", "host")
        })
      }
      for ((name, spec) in contract.inputs.entries.sortedBy { it.key }) {
        if (name.startsWith("theme.") || spec.role != null) { skipped += "$name (theme role)"; continue }
        add(name, spec)
      }
      for ((list, spec) in contract.lists.entries.sortedBy { it.key }) {
        add("$list.count", InputSpec(ValueType.Int))
        repeat((items ?: spec.max).coerceIn(0, spec.max)) { i ->
          for ((field, f) in spec.item.entries.sortedBy { it.key }) add("$list.$i.$field", f)
        }
      }
    }
    return Result(vars, skipped)
  }

  private fun initial(valueType: String, default: JsonElement?): JsonPrimitive {
    val p = default as? JsonPrimitive
    return when (valueType) {
      "bool" -> JsonPrimitive(p?.content?.toBooleanStrictOrNull() ?: false)
      "int" -> JsonPrimitive(p?.content?.toDoubleOrNull()?.toLong() ?: 0L)
      "float" -> JsonPrimitive(p?.content?.toDoubleOrNull() ?: 0.0)
      else -> JsonPrimitive(p?.content ?: "")
    }
  }
}
