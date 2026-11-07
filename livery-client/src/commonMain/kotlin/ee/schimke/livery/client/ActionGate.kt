package ee.schimke.livery.client

import ee.schimke.livery.protocol.ActionKinds
import ee.schimke.livery.protocol.ActionSpec
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.InputSpec
import ee.schimke.livery.protocol.ValueType

/**
 * PROTOCOL.md §7: an emitted action against its contract. [args] are the arguments the action
 * declares, checked against their types, and the only ones an event or analytics pipeline ever
 * sees: an argument the contract doesn't declare still reaches the app's handler, but never a log.
 * [problem] is why the action is dropped (undeclared, a required argument missing, a value that
 * doesn't fit its declaration).
 */
data class ActionCheck(val spec: ActionSpec?, val args: Map<String, String>, val problem: String?)

/** The one gate for actions, as [ContractBinder] is for data; pinned with Swift by `conformance/actions.json`. */
object ActionGate {
  /** The value types an action argument can carry: what a host named action's text holds. */
  val ARG_TYPES: Set<ValueType> = setOf(ValueType.String, ValueType.Int, ValueType.Long, ValueType.Float, ValueType.Bool)

  fun check(contract: Contract, action: String, args: Map<String, String>): ActionCheck {
    val spec = contract.actions[action] ?: return ActionCheck(null, emptyMap(), "undeclared action")
    if (spec.kind == ActionKinds.SET) return checkSet(contract, spec, args)
    val declared = LinkedHashMap<String, String>()
    for (name in spec.args.keys.sorted()) {
      val s = spec.args.getValue(name)
      val value = args[name]
      if (value == null) {
        if (s.required) return ActionCheck(spec, emptyMap(), "missing argument '$name'")
        continue
      }
      problem(name, s, value)?.let { return ActionCheck(spec, emptyMap(), it) }
      declared[name] = value
    }
    return ActionCheck(spec, declared, null)
  }

  /** Problems with an action's declared arguments, for the publisher. */
  fun declarationProblems(name: String, spec: ActionSpec): List<String> = spec.args.mapNotNull { (arg, s) ->
    if (s.type !in ARG_TYPES) "action $name argument $arg is ${s.type.name.lowercase()}: an action argument is a string, int, long, float or bool" else null
  }

  /**
   * Problems with a contract's `set` actions and `input` fields, for the publisher (PROTOCOL.md
   * §7.1): a `set` names one of the contract's own inputs, of a type an action can carry, that isn't
   * a theme role; it declares no arguments of its own (its `value` is the input's); and only a
   * `set` names an input.
   */
  fun setProblems(contract: Contract): List<String> = contract.actions.entries.sortedBy { it.key }.flatMap { (name, spec) ->
    when {
      spec.kind != ActionKinds.SET -> listOfNotNull(spec.input?.let { "action $name names an input but isn't `set`" })
      spec.input.isNullOrBlank() -> listOf("action $name is `set` but names no input")
      else -> {
        val input = contract.inputs[spec.input]
        listOfNotNull(
          when {
            input == null -> "action $name sets ${spec.input}, which isn't one of the contract's inputs"
            input.role != null -> "action $name sets ${spec.input}, a theme role, which the host fills"
            input.type !in ARG_TYPES -> "action $name sets ${spec.input}, a ${input.type.name.lowercase()}, which an action can't carry"
            else -> null
          },
          spec.args.takeIf { it.isNotEmpty() }?.let { "action $name is `set`: its one argument is value, checked as ${spec.input} is, so it declares none" },
        )
      }
    }
  }

  /**
   * A `set`: its one argument, `value`, required and checked as the input it names. A bool also
   * takes `1` and `0`, which is how a document's boolean reaches the host, and is passed on as
   * `true` or `false`.
   */
  private fun checkSet(contract: Contract, spec: ActionSpec, args: Map<String, String>): ActionCheck {
    val input = spec.input?.let(contract.inputs::get) ?: return ActionCheck(spec, emptyMap(), "set names no input of the contract")
    val raw = args[VALUE] ?: return ActionCheck(spec, emptyMap(), "missing argument '$VALUE'")
    val value = if (input.type == ValueType.Bool) when (raw) { "1" -> "true"; "0" -> "false"; else -> raw } else raw
    problem(VALUE, input, value)?.let { return ActionCheck(spec, emptyMap(), it) }
    return ActionCheck(spec, mapOf(VALUE to value), null)
  }

  /** A `set` action's one argument. */
  const val VALUE: String = "value"

  /** Whether the client handles [kind] itself: a `track` action is an event and nothing else. */
  fun isTrackOnly(kind: String): Boolean = kind == ActionKinds.TRACK

  private fun problem(name: String, s: InputSpec, value: String): String? {
    fun range(n: Double): String? = when {
      s.min != null && n < s.min!! -> "argument '$name' is below ${s.min}"
      s.max != null && n > s.max!! -> "argument '$name' is above ${s.max}"
      else -> null
    }
    fun number(n: Double?, type: String): String? = if (n == null || !n.isFinite()) "argument '$name' is not $type" else range(n)
    return when (s.type) {
      ValueType.String -> s.maxLength?.takeIf { value.length > it }?.let { "argument '$name' is longer than $it" }
      ValueType.Int -> number(value.toIntOrNull()?.toDouble(), "an int")
      ValueType.Long -> number(value.toLongOrNull()?.toDouble(), "a long")
      ValueType.Float -> number(value.toDoubleOrNull(), "a float")
      ValueType.Bool -> if (value == "true" || value == "false") null else "argument '$name' is not a bool"
      ValueType.Color, ValueType.Image -> "argument '$name' is ${s.type.name.lowercase()}, which an action can't carry"
    }
  }
}
