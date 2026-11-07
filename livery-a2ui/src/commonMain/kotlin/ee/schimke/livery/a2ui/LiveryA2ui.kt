package ee.schimke.livery.a2ui

import ee.schimke.livery.protocol.ActionKinds
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.InputSpec
import ee.schimke.livery.protocol.ValueType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One property of a Livery component, in A2UI's terms (the kinds A2UI's own properties have). */
data class A2uiPropertySpec(
  val key: String,
  val kind: Kind,
  val required: Boolean = false,
  val description: String? = null,
  /** For [Kind.Actions]: one [Kind.Action] per contract action. */
  val properties: List<A2uiPropertySpec> = emptyList(),
) {
  enum class Kind {
    /** A literal, a `{"path": …}` binding or a function call, resolving to a string. */
    DynamicString,
    DynamicNumber,
    DynamicBoolean,
    /** Any JSON value, literal or bound: a contract list. */
    DynamicValue,
    /** A literal string. */
    String,
    /** An A2UI `Action`: an `event` to the agent or a `functionCall`. */
    Action,
    /** An object of [Action]s, no other keys. */
    Actions,
    /** Any literal JSON. */
    Any,
  }

  /** The property's JSON schema, as A2UI advertises it in a catalog. */
  fun schema(): JsonObject = buildJsonObject {
    when (kind) {
      Kind.DynamicString -> put("\$ref", "$COMMON#/\$defs/DynamicString")
      Kind.DynamicNumber -> put("\$ref", "$COMMON#/\$defs/DynamicNumber")
      Kind.DynamicBoolean -> put("\$ref", "$COMMON#/\$defs/DynamicBoolean")
      Kind.DynamicValue -> put("\$ref", "$COMMON#/\$defs/DynamicValue")
      Kind.String -> put("type", "string")
      Kind.Action -> put("\$ref", "$COMMON#/\$defs/Action")
      Kind.Actions -> {
        put("type", "object")
        put("properties", JsonObject(properties.associate { it.key to it.schema() }))
        put("additionalProperties", false)
      }
      Kind.Any -> {}
    }
    description?.let { put("description", it) }
  }

  private companion object {
    const val COMMON = "common_types.json"
  }
}

/** What one Livery contract becomes in an A2UI catalog. */
data class A2uiComponentSpec(
  val contract: Contract,
  val name: String,
  val description: String,
  val properties: List<A2uiPropertySpec>,
) {
  /** The component's JSON schema, as A2UI advertises it: a `component` const, its properties, what's required. */
  fun schema(): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
      put("component", buildJsonObject { put("type", "string"); put("const", name) })
      for (p in properties) put(p.key, p.schema())
    })
    put("required", buildJsonArray { add(JsonPrimitive("component")); properties.filter { it.required }.forEach { add(JsonPrimitive(it.key)) } })
    put("description", description)
  }
}

/**
 * Livery contracts in A2UI's terms (docs/A2UI.md), with no A2UI library: the one place a contract
 * becomes a component, its schema, and the data and events that cross between A2UI and Livery.
 * Every renderer builds on it, and `conformance/a2ui.json` pins it, so they agree.
 */
object LiveryA2ui {
  const val DESIGN: String = "design"
  const val VARIANT: String = "variant"
  const val ACTIONS: String = "actions"
  const val TEMPLATE: String = "template"

  /** The inputs an agent gives: every input but the theme roles, which the host fills. */
  fun agentInputs(contract: Contract): Map<String, InputSpec> = contract.inputs.filterValues { it.role == null }

  /** The inputs the contract's `set` actions change, sorted (an A2UI renderer binds a writer for each). */
  fun setInputs(contract: Contract): List<String> =
    contract.actions.values.filter { it.kind == ActionKinds.SET }.mapNotNull { it.input }.filter { it in agentInputs(contract) }.distinct().sorted()

  /** The component [contract] becomes: its agent inputs, lists, `design`, `variant`, `actions` and `template`. */
  fun component(contract: Contract, name: String = componentName(contract)): A2uiComponentSpec {
    val inputs = agentInputs(contract).map { (key, spec) ->
      A2uiPropertySpec(
        key,
        when (spec.type) {
          ValueType.String, ValueType.Color, ValueType.Image -> A2uiPropertySpec.Kind.DynamicString
          ValueType.Int, ValueType.Long, ValueType.Float -> A2uiPropertySpec.Kind.DynamicNumber
          ValueType.Bool -> A2uiPropertySpec.Kind.DynamicBoolean
        },
        // Required to the agent only when the template has no default to draw instead.
        required = spec.required && spec.default == null,
        description = describe(spec),
      )
    }
    val lists = contract.lists.map { (key, spec) ->
      A2uiPropertySpec(
        key, A2uiPropertySpec.Kind.DynamicValue,
        description = listOfNotNull(spec.description, "An array of at most ${spec.max} objects, each with ${spec.item.keys.joinToString()}.").joinToString(" "),
      )
    }
    val actions = contract.actions.map { (key, spec) ->
      A2uiPropertySpec(
        key, A2uiPropertySpec.Kind.Action,
        description = listOfNotNull(
          spec.description,
          if (spec.kind == ActionKinds.SET) "Writes ${spec.input} back through its data-model path, so bind ${spec.input} to one; an event here also gets the new value as value." else "Kind ${spec.kind}.",
          spec.args.keys.takeIf { it.isNotEmpty() }?.let { "Adds ${it.joinToString()} to an event's context." },
        ).joinToString(" "),
      )
    }
    return A2uiComponentSpec(
      contract,
      name,
      listOfNotNull(contract.title, "Livery contract ${contract.ref}: a server-driven template; its look is chosen by the client.").joinToString(". "),
      inputs + lists + listOf(
        A2uiPropertySpec(DESIGN, A2uiPropertySpec.Kind.String, description = "A Livery design id to prefer; unavailable falls back to the automatic choice."),
        A2uiPropertySpec(VARIANT, A2uiPropertySpec.Kind.String, description = "A layout variant to prefer, such as compact or hero."),
        A2uiPropertySpec(ACTIONS, A2uiPropertySpec.Kind.Actions, description = "What each of the template's actions does; an action left out is handled by the app.", properties = actions),
        A2uiPropertySpec(
          TEMPLATE, A2uiPropertySpec.Kind.Any,
          description = "Usually left out: the client picks a reviewed design. To send the document itself: " +
            "{\"inline\": <base64 Remote Compose bytes>, \"signature\": {\"keyId\", \"signature\"}}; unsigned bytes play only " +
            "where the app allows them, and never with shaders or other risky features.",
        ),
      ),
    )
  }

  /**
   * The A2UI catalog of [contracts]' components alone, as `livery build --a2ui` writes it for an
   * agent: `$schema`, `$id` and `catalogId` both [catalogId], and `components`. A client
   * advertises these beside the basic catalog's.
   */
  fun catalogSchema(catalogId: String, contracts: List<Contract>): JsonObject = buildJsonObject {
    put("\$schema", "https://json-schema.org/draft/2020-12/schema")
    put("\$id", catalogId)
    put("catalogId", catalogId)
    put("components", JsonObject(newestMinors(contracts).map(::component).associate { it.name to it.schema() }))
  }

  /** One contract per major, at the newest minor (Manifest.contract): one component per major. */
  fun newestMinors(contracts: List<Contract>): List<Contract> =
    contracts.groupBy { it.ref }.values.map { v -> v.maxBy { it.minor } }.sortedBy { it.ref }

  /** `promo/banner@1` is `PromoBanner`; `promo/banner@2` is `PromoBannerV2`. */
  fun componentName(contract: Contract): String {
    val base = contract.id.split('/', '-', '_', '.').filter { it.isNotEmpty() }.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    return if (contract.major > 1) "${base}V${contract.major}" else base
  }

  /**
   * The dataset for [contract] from the component's bound properties: [resolved] maps a property
   * key to what A2UI resolved it to (a string, number, bool, list or map; absent when unbound or
   * pending). Only agent inputs and lists are kept, as given: ContractBinder decides whether a value
   * fits, never a narrowing here.
   */
  fun dataset(contract: Contract, resolved: Map<String, Any?>): JsonObject {
    val keys = agentInputs(contract).keys + contract.lists.keys
    return JsonObject(keys.mapNotNull { key -> resolved[key]?.let { key to toJson(it) } }.toMap())
  }

  /** Theme role inputs from the host's colours by role (`primary` → ARGB), as `#AARRGGBB`. */
  fun themeValues(contract: Contract, argbByRole: Map<String, Long>): JsonObject = JsonObject(
    contract.inputs.mapNotNull { (key, spec) ->
      val argb = spec.role?.let(argbByRole::get)?.takeIf { spec.type == ValueType.Color } ?: return@mapNotNull null
      key to JsonPrimitive("#" + (argb and 0xFFFFFFFFL).toString(16).padStart(8, '0').uppercase())
    }.toMap(),
  )

  /** A `set`'s value, already checked by ActionGate, as the data model holds the input's type. */
  fun writable(value: String?, type: ValueType): Any? = when (type) {
    ValueType.Int, ValueType.Long -> value?.toLongOrNull()
    ValueType.Float -> value?.toDoubleOrNull()
    ValueType.Bool -> value?.toBooleanStrictOrNull()
    ValueType.String, ValueType.Color, ValueType.Image -> value
  }

  /** An agent's action with a template's checked arguments in it, and the context keys they shadowed. */
  data class EventPayload(val action: Map<String, Any?>, val shadowed: Set<String>)

  /**
   * The agent's A2UI [action] with the template's declared, checked [args] added to an event's
   * context (they win over a context key of the same name, which [EventPayload.shadowed] reports
   * as the agent's mistake). A `functionCall` is the agent's own and passes as it is.
   */
  fun eventPayload(action: Map<String, Any?>, args: Map<String, String>): EventPayload {
    @Suppress("UNCHECKED_CAST")
    val event = action["event"] as? Map<String, Any?> ?: return EventPayload(action, emptySet())
    @Suppress("UNCHECKED_CAST")
    val context = event["context"] as? Map<String, Any?> ?: emptyMap()
    return EventPayload(action + ("event" to (event + ("context" to (context + args)))), (context.keys intersect args.keys).sorted().toSet())
  }

  /** Any value A2UI resolves to, as JSON. */
  fun toJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
    is Iterable<*> -> JsonArray(value.map(::toJson))
    is Array<*> -> JsonArray(value.map(::toJson))
    else -> JsonPrimitive(value.toString())
  }

  private fun describe(spec: InputSpec): String = listOfNotNull(
    spec.description,
    spec.maxLength?.let { "At most $it characters." },
    spec.min?.let { "At least ${number(it)}." },
    spec.max?.let { "At most ${number(it)}." },
    when (spec.type) {
      ValueType.Color -> "A colour, #RRGGBB or #AARRGGBB."
      ValueType.Image -> "An image: asset:<sha256>, https://… or app:<key>."
      ValueType.Int, ValueType.Long -> "A whole number."
      else -> null
    },
    spec.default?.let { "Default $it." },
  ).joinToString(" ").ifEmpty { spec.type.name.lowercase() }

  /** The same text on every platform: a whole number has no `.0` (JS and the JVM print 0.0 differently). */
  private fun number(d: Double): String = if (d % 1.0 == 0.0 && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()
}
