package ee.schimke.livery.capture

import ee.schimke.livery.client.ActionGate
import ee.schimke.livery.client.ContractBinder
import ee.schimke.livery.client.ContractEvolution
import ee.schimke.livery.protocol.ActionKinds
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.InputSpec
import ee.schimke.livery.protocol.ValueType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What `LiveryValues` checks before it hands out a named value or an action, with no creation API
 * in it: a name the [contract] doesn't declare, or the wrong type, throws; a declared one gives the
 * contract's default, which the captured document draws when data leaves the value out. Both
 * bindings' `LiveryValues` (AndroidX's in `livery-capture`, rc-players' in `livery-capture-cmp`)
 * are this plus the `Remote*` types of their creation API, so they cannot disagree about a contract.
 */
class ContractChecks(val contract: Contract) {
  fun string(name: String): String = default(name, ValueType.String)?.contentOrEmpty() ?: ""

  /** An int input, or a list's `<list>.count`. */
  fun int(name: String): Int = (default(name, ValueType.Int, countOk = true) as? JsonPrimitive)?.longOrNull?.toInt() ?: 0

  fun long(name: String): Long = (default(name, ValueType.Long) as? JsonPrimitive)?.longOrNull ?: 0

  fun float(name: String): Float = (default(name, ValueType.Float) as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: 0f

  fun bool(name: String): Boolean = (default(name, ValueType.Bool) as? JsonPrimitive)?.booleanOrNull ?: false

  /** A colour input's or a `theme.*` role's default as ARGB, or null when the contract gives none. */
  fun color(name: String): Int? =
    (default(name, ValueType.Color, themeOk = true) as? JsonPrimitive)?.contentOrEmpty()?.let(ContractBinder::parseColor)?.toInt()

  /**
   * The payload a tap on action [name] sends with [args]: their JSON object, or the action's name
   * with none. Each argument must be one the action declares, of its type, with every required one
   * given (the client checks the same, PROTOCOL.md §7).
   */
  fun actionPayload(name: String, args: Map<String, String> = emptyMap()): String {
    require(name in contract.actions) { "${contract.ref} declares no action '$name': ${contract.actions.keys.sorted()}" }
    val undeclared = args.keys - contract.actions.getValue(name).args.keys
    require(undeclared.isEmpty()) { "${contract.ref} action '$name' declares no arguments ${undeclared.sorted()}" }
    ActionGate.check(contract, name, args).problem?.let { throw IllegalArgumentException("${contract.ref} action '$name': $it") }
    return if (args.isEmpty()) name else Json.encodeToString(JsonObject.serializer(), JsonObject(args.entries.sortedBy { it.key }.associate { it.key to JsonPrimitive(it.value) }))
  }

  /** That [name] is a `set` action (PROTOCOL.md §7.1) whose input is one of [types]. */
  fun requireSet(name: String, vararg types: ValueType) {
    val spec = contract.actions[name] ?: throw IllegalArgumentException("${contract.ref} declares no action '$name': ${contract.actions.keys.sorted()}")
    require(spec.kind == ActionKinds.SET) { "${contract.ref} action '$name' is ${spec.kind}, not set" }
    val input = contract.inputs[spec.input] ?: throw IllegalArgumentException("${contract.ref} action '$name' sets no input of the contract")
    require(input.type in types) { "${contract.ref} action '$name' sets ${spec.input}, a ${input.type.name.lowercase()}, not ${types.joinToString(" or ") { it.name.lowercase() }}" }
  }

  private fun default(name: String, type: ValueType, countOk: Boolean = false, themeOk: Boolean = false): JsonElement? {
    if (themeOk && name.startsWith("theme.") && ContractEvolution.specOf(contract, name) == null) return null
    if (countOk && contract.lists.keys.any { name == "$it.count" }) return null
    val spec: InputSpec = ContractEvolution.specOf(contract, name)
      ?: throw IllegalArgumentException("${contract.ref} declares no value '$name'")
    require(spec.type == type) { "${contract.ref} declares '$name' as ${spec.type.name.lowercase()}, not ${type.name.lowercase()}" }
    return spec.default
  }

  private fun JsonElement.contentOrEmpty(): String = (this as? JsonPrimitive)?.content ?: ""
}
