package ee.schimke.livery.client

import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.ImageRef
import ee.schimke.livery.protocol.InputSpec
import ee.schimke.livery.protocol.NamedValue
import ee.schimke.livery.protocol.ValueType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** The flat named values a player is handed: what it pushes live, or what instantiation bakes in. */
data class Bindings(val values: Map<String, NamedValue>) {
  operator fun plus(other: Bindings) = Bindings(values + other.values)

  companion object {
    val EMPTY = Bindings(emptyMap())
  }
}

data class BindingProblem(val name: String, val message: String)

sealed interface BindResult {
  data class Ok(val bindings: Bindings) : BindResult
  data class Invalid(val problems: List<BindingProblem>) : BindResult
}

/**
 * Checks a dataset against a contract and flattens it into named values. The same gate runs on
 * the device (decision and feed payloads), in the publisher (examples), on a server, and in agent
 * tools (an agent's generated copy), so "valid" means the same thing everywhere.
 *
 * Unknown keys are refused, not dropped: a typo in a campaign should fail review, not ship blank.
 */
object ContractBinder {
  fun bind(contract: Contract, data: JsonObject): BindResult {
    val problems = mutableListOf<BindingProblem>()
    val out = LinkedHashMap<String, NamedValue>()

    val known = contract.inputs.keys + contract.lists.keys
    data.keys.filterNot { it in known }.forEach { problems += BindingProblem(it, "not in ${contract.ref}") }

    for ((name, spec) in contract.inputs) {
      val raw = data[name]?.takeUnless { it is JsonNull } ?: spec.default
      if (raw == null) {
        if (spec.required) problems += BindingProblem(name, "required")
        continue
      }
      coerce(name, spec, raw, problems)?.let { out[name] = it }
    }

    for ((name, list) in contract.lists) {
      val raw = data[name] ?: continue
      val items = raw as? JsonArray ?: run { problems += BindingProblem(name, "expected a list"); continue }
      if (items.size > list.max) problems += BindingProblem(name, "${items.size} items, at most ${list.max}")
      out["$name.count"] = NamedValue.I(minOf(items.size, list.max))
      items.take(list.max).forEachIndexed { i, item ->
        val obj = item as? JsonObject ?: run { problems += BindingProblem("$name.$i", "expected an object"); return@forEachIndexed }
        obj.keys.filterNot { it in list.item }.forEach { problems += BindingProblem("$name.$i.$it", "not in list item") }
        for ((field, spec) in list.item) {
          val key = "$name.$i.$field"
          val v = obj[field]?.takeUnless { it is JsonNull } ?: spec.default
          if (v == null) { if (spec.required) problems += BindingProblem(key, "required"); continue }
          coerce(key, spec, v, problems)?.let { out[key] = it }
        }
      }
    }
    return if (problems.isEmpty()) BindResult.Ok(Bindings(out)) else BindResult.Invalid(problems)
  }

  private fun coerce(name: String, spec: InputSpec, raw: JsonElement, problems: MutableList<BindingProblem>): NamedValue? {
    val p = raw as? JsonPrimitive ?: run { problems += BindingProblem(name, "expected a ${spec.type}"); return null }
    fun bad(): NamedValue? { problems += BindingProblem(name, "expected a ${spec.type}, got $p"); return null }
    fun ranged(d: Double): Boolean {
      if (spec.min != null && d < spec.min!!) { problems += BindingProblem(name, "$d < ${spec.min}"); return false }
      if (spec.max != null && d > spec.max!!) { problems += BindingProblem(name, "$d > ${spec.max}"); return false }
      return true
    }
    return when (spec.type) {
      ValueType.String -> {
        if (!p.isString) return bad()
        val s = p.content
        if (spec.maxLength != null && s.length > spec.maxLength!!) {
          problems += BindingProblem(name, "${s.length} chars, at most ${spec.maxLength}"); null
        } else NamedValue.Str(s)
      }
      ValueType.Int -> integral(p)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.let { if (ranged(it.toDouble())) NamedValue.I(it.toInt()) else null } ?: bad()
      ValueType.Long -> integral(p)?.let { if (ranged(it.toDouble())) NamedValue.L(it) else null } ?: bad()
      ValueType.Float -> p.doubleOrNull?.takeIf { !p.isString }?.let { if (ranged(it)) NamedValue.F(it.toFloat()) else null } ?: bad()
      ValueType.Bool -> p.booleanOrNull?.takeIf { !p.isString }?.let { NamedValue.B(it) } ?: bad()
      ValueType.Color -> parseColor(p.content)?.takeIf { p.isString }?.let { NamedValue.Color(it) } ?: bad()
      ValueType.Image ->
        if (p.isString && ImageRef.parse(p.content) != null) NamedValue.Image(p.content)
        else { problems += BindingProblem(name, "expected an image: asset:<sha256>, https://… or app:<key>, got $p"); null }
    }
  }

  /**
   * A JSON number with a whole-number value: `3`, `3.0` and `3e0` alike (PROTOCOL.md §4.1), since
   * not every platform's JSON parser keeps the token's spelling. Beyond 2^53 only an integer
   * literal is exact, so a fractional or exponent spelling there is refused.
   */
  private fun integral(p: JsonPrimitive): Long? {
    if (p.isString) return null
    p.longOrNull?.let { return it }
    val d = p.doubleOrNull ?: return null
    return if (d == kotlin.math.floor(d) && kotlin.math.abs(d) <= 9.007199254740992E15) d.toLong() else null
  }

  /** `#RRGGBB` or `#AARRGGBB` → ARGB. */
  fun parseColor(s: String): Long? {
    if (!s.startsWith('#')) return null
    val hex = s.substring(1)
    if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    val v = hex.toLongOrNull(16) ?: return null
    return when (hex.length) {
      6 -> 0xFF000000L or v
      8 -> v
      else -> null
    }
  }
}
