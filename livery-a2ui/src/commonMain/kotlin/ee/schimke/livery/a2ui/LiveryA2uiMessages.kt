package ee.schimke.livery.a2ui

import ee.schimke.livery.client.BindResult
import ee.schimke.livery.client.ContractBinder
import ee.schimke.livery.protocol.Contract
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * An agent's A2UI messages checked against Livery's contracts before they are sent: what a client
 * would refuse or draw as its fallback, said where the agent wrote it. Only Livery components are
 * checked; the basic catalog's are A2UI's to validate.
 */
object LiveryA2uiMessages {
  /** One problem: the component (its `id`), the property it is in (if one), and what is wrong. */
  data class Problem(val component: String, val property: String?, val message: String)

  /** Keys every A2UI component may carry besides its own properties. */
  private val COMMON = setOf("id", "component", "weight", "accessibility")

  /**
   * Checks every Livery component in [messages] (one message, or an array of them): its properties
   * against [contracts]' projection, its literal values (and, with [dataModel], its bound ones)
   * through [ContractBinder] as every client binds them, its `actions` and `template`, and its
   * `design` against [designs] (design ids by contract ref) when given.
   */
  fun validate(
    messages: JsonElement,
    contracts: List<Contract>,
    dataModel: JsonElement? = null,
    designs: Map<String, Set<String>>? = null,
  ): List<Problem> {
    val byName = LiveryA2ui.newestMinors(contracts).associateBy { LiveryA2ui.componentName(it) }
    val list = (messages as? JsonArray)?.toList() ?: listOf(messages)
    return list.flatMap { m ->
      val components = ((m as? JsonObject)?.get("updateComponents") as? JsonObject)?.get("components") as? JsonArray ?: return@flatMap emptyList()
      components.mapNotNull { it as? JsonObject }.flatMap { c ->
        val name = (c["component"] as? JsonPrimitive)?.content
        val contract = name?.let(byName::get) ?: return@flatMap emptyList()
        // A known catalog with no designs for this contract still checks: no design is one of its.
        component(c, contract, dataModel, designs?.let { it[contract.ref].orEmpty() })
      }
    }
  }

  private fun component(c: JsonObject, contract: Contract, dataModel: JsonElement?, designs: Set<String>?): List<Problem> {
    val id = (c["id"] as? JsonPrimitive)?.content ?: "?"
    val spec = LiveryA2ui.component(contract)
    val known = spec.properties.associateBy { it.key }
    val problems = mutableListOf<Problem>()
    fun problem(property: String?, message: String) { problems += Problem(id, property, message) }

    for (key in c.keys - COMMON - known.keys) problem(key, "${spec.name} has no property $key (${contract.ref}: ${known.keys.sorted()})")
    for (p in spec.properties.filter { it.required && it.key !in c }) problem(p.key, "${p.key} is required: the contract has no default for it")

    for (key in listOf(LiveryA2ui.DESIGN, LiveryA2ui.VARIANT)) {
      val v = c[key] ?: continue
      if (v !is JsonPrimitive || !v.isString) problem(key, "$key must be a string")
    }
    val design = (c[LiveryA2ui.DESIGN] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (design != null && designs != null && design !in designs) {
      problem(LiveryA2ui.DESIGN, "no design $design for ${contract.ref} (${designs.sorted()}): the client falls back to its automatic choice")
    }

    c[LiveryA2ui.ACTIONS]?.let { actions ->
      if (actions !is JsonObject) { problem(LiveryA2ui.ACTIONS, "actions must be an object of A2UI actions by contract action name"); return@let }
      for ((name, action) in actions) {
        if (name !in contract.actions) problem("${LiveryA2ui.ACTIONS}.$name", "${contract.ref} declares no action $name (${contract.actions.keys.sorted()})")
        else if (action !is JsonObject || ("event" !in action && "functionCall" !in action)) problem("${LiveryA2ui.ACTIONS}.$name", "an action is {\"event\": …} or {\"functionCall\": …}")
      }
    }

    c[LiveryA2ui.TEMPLATE]?.let { template -> templateProblems(template).forEach { problem(LiveryA2ui.TEMPLATE, it) } }

    // The values: literals as written, bindings resolved when the data model is given, then the one gate.
    val keys = LiveryA2ui.agentInputs(contract).keys + contract.lists.keys
    val resolved = LinkedHashMap<String, Any?>()
    for (key in keys) {
      val v = c[key] ?: continue
      when {
        v is JsonObject && "path" in v -> {
          // A2UI's binding is a JSON string path; a number, boolean or null isn't one.
          val path = (v["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: run { problem(key, "path must be a string"); null }
          if (path != null && dataModel != null) {
            val found = pointer(dataModel, path)
            if (found == null) problem(key, "nothing in the data model at $path") else resolved[key] = found
          }
        }
        v is JsonObject && "call" in v -> {} // A function call: resolved by the client.
        else -> resolved[key] = v
      }
    }
    when (val b = ContractBinder.bind(contract, LiveryA2ui.dataset(contract, resolved))) {
      is BindResult.Ok -> {}
      // Only the values given: a required input left to a binding isn't missing here.
      is BindResult.Invalid -> b.problems.filter { it.name in resolved || it.name.substringBefore('.') in resolved }
        .forEach { problem(it.name, it.message) }
    }
    return problems
  }

  private fun templateProblems(t: JsonElement): List<String> =
    (A2uiTemplate.parse(t) as? A2uiTemplate.Invalid)?.let { listOf(it.reason) }.orEmpty()

  /** The value at JSON pointer [path] (`/a/b/0`) in [root], or null. */
  private fun pointer(root: JsonElement, path: String): JsonElement? {
    if (path.isEmpty() || path == "/") return root
    var at: JsonElement = root
    for (raw in path.removePrefix("/").split('/')) {
      val part = raw.replace("~1", "/").replace("~0", "~")
      at = when (at) {
        is JsonObject -> at[part] ?: return null
        is JsonArray -> part.toIntOrNull()?.let(at::getOrNull) ?: return null
        else -> return null
      }
    }
    return at
  }
}

/**
 * A Livery component as an agent writes it into `updateComponents`, built against its contract so
 * a misspelt input or action fails here rather than on the device.
 *
 * ```
 * LiveryA2ui.componentJson("promo", promoBanner) {
 *   path("headline", "/offer/title"); value("cta", "Shop now")
 *   event("cta", "startTrial", context = mapOf("from" to "chat"))
 * }
 * ```
 */
class LiveryComponentBuilder internal constructor(private val contract: Contract) {
  private val props = LinkedHashMap<String, JsonElement>()
  private val actions = LinkedHashMap<String, JsonElement>()
  private val values = LiveryA2ui.agentInputs(contract).keys + contract.lists.keys

  private fun input(name: String) = name.also { require(it in values) { "${contract.ref} has no input or list $it (${values.sorted()})" } }

  /** [name] as a literal (a string, number, boolean, list or map). */
  fun value(name: String, value: Any?) { props[input(name)] = LiveryA2ui.toJson(value) }

  /** [name] bound to the data model at [path]: the client redraws as it changes, and a `set` writes it. */
  fun path(name: String, path: String) { props[input(name)] = buildJsonObject { put("path", path) } }

  fun design(id: String) { props[LiveryA2ui.DESIGN] = JsonPrimitive(id) }

  fun variant(name: String) { props[LiveryA2ui.VARIANT] = JsonPrimitive(name) }

  /** The template's action [action] sends the agent event [name], with [context] (the action's declared arguments are added to it). */
  fun event(action: String, name: String, context: Map<String, Any?> = emptyMap()) {
    require(action in contract.actions) { "${contract.ref} declares no action $action (${contract.actions.keys.sorted()})" }
    actions[action] = buildJsonObject {
      put("event", buildJsonObject {
        put("name", name)
        if (context.isNotEmpty()) put("context", LiveryA2ui.toJson(context))
      })
    }
  }

  internal fun build(id: String): JsonObject = buildJsonObject {
    put("id", id)
    put("component", LiveryA2ui.componentName(contract))
    props.forEach { (k, v) -> put(k, v) }
    if (actions.isNotEmpty()) put(LiveryA2ui.ACTIONS, JsonObject(actions))
  }
}

/** The `updateComponents` entry for a Livery component [id] of [contract]. */
fun LiveryA2ui.componentJson(id: String, contract: Contract, block: LiveryComponentBuilder.() -> Unit): JsonObject =
  LiveryComponentBuilder(contract).apply(block).build(id)
