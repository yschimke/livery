package ee.schimke.livery.a2ui.compose

import androidx.a2ui.compose.runtime.A2uiComponentProperties
import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.runtime.A2uiProperty
import androidx.a2ui.compose.runtime.DynamicA2uiProperty
import androidx.a2ui.compose.runtime.StaticA2uiProperty
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.a2ui.model.protocol.A2uiException
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import ee.schimke.livery.client.ActionRouter
import ee.schimke.livery.client.PlanResult
import ee.schimke.livery.client.RenderPlan
import ee.schimke.livery.client.TemplateQuery
import ee.schimke.livery.compose.LiveryHost
import ee.schimke.livery.compose.LiveryTemplate
import ee.schimke.livery.protocol.ActionKinds
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.InputSpec
import ee.schimke.livery.protocol.TemplateEvent
import ee.schimke.livery.protocol.ValueType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One Livery contract as one A2UI component (docs/A2UI.md): a slot typed by the contract, never a
 * description of what the document draws.
 *
 * The agent gives the contract's inputs (literals, `{"path": …}` bindings or function calls), an
 * optional `design` / `variant` preference, and `actions`: an A2UI `Action` per contract action
 * name. The component binds the inputs against the surface's data model, passes them through the
 * one gate ([ee.schimke.livery.client.ContractBinder], via the planner), lets
 * [ee.schimke.livery.client.TemplateSelector] pick an installed, verified template for the space
 * A2UI gives it, and plays it with [LiveryTemplate], so a data-model update is pushed into the
 * playing document. A document's host action passes [ee.schimke.livery.client.ActionGate], then
 * goes to the agent when it wired that action, or to the app's handler for its kind when it didn't.
 *
 * Theme role inputs (`"role": "primary"`) are not advertised to the agent: they come from the
 * host's Material colour scheme, so the template matches the components around it.
 */
class LiveryA2uiComponent(
  val contract: Contract,
  private val host: LiveryA2uiHost,
  override val name: String = LiveryA2uiNames.componentName(contract),
) : A2uiComponent {

  override val description: String =
    listOfNotNull(contract.title, "Livery contract ${contract.ref}: a server-driven template; its look is chosen by the client.")
      .joinToString(". ")

  /** The inputs an agent gives: every input but the theme roles, which the host fills. */
  private val agentInputs: Map<String, InputSpec> = contract.inputs.filterValues { it.role == null }

  private val inputProperties: Map<String, DynamicA2uiProperty<Any>> =
    agentInputs.mapValues { (key, spec) -> inputProperty(key, spec) }

  private val listProperties: Map<String, DynamicA2uiProperty<Any>> =
    contract.lists.mapValues { (key, spec) ->
      A2uiProperty.dynamicValue(
        key,
        description = listOfNotNull(spec.description, "An array of at most ${spec.max} objects, each with ${spec.item.keys.joinToString()}.").joinToString(" "),
      )
    }

  private val designProperty: StaticA2uiProperty<String> =
    A2uiProperty.string(DESIGN, description = "A Livery design id to prefer; unavailable falls back to the automatic choice.")

  private val variantProperty: StaticA2uiProperty<String> =
    A2uiProperty.string(VARIANT, description = "A layout variant to prefer, such as compact or hero.")

  private val actionProperties: Map<String, StaticA2uiProperty<Map<String, Any?>>> =
    contract.actions.mapValues { (key, spec) ->
      A2uiProperty.action(
        key,
        description = listOfNotNull(
          spec.description,
          "Kind ${spec.kind}.",
          spec.args.keys.takeIf { it.isNotEmpty() }?.let { "Adds ${it.joinToString()} to an event's context." },
        ).joinToString(" "),
      )
    }

  private val actionsProperty: StaticA2uiProperty<A2uiComponentProperties> =
    A2uiProperty.nested(
      ACTIONS,
      properties = actionProperties.values.toList(),
      description = "What each of the template's actions does; an action left out is handled by the app.",
      isAdditionalPropertiesAllowed = false,
    )

  override val properties: List<A2uiProperty<*>> =
    inputProperties.values + listProperties.values + listOf(designProperty, variantProperty, actionsProperty)

  @Composable
  override fun A2uiComponentScope.isReady(properties: A2uiComponentProperties): Boolean =
    agentInputs.all { (key, spec) -> !spec.required || spec.default != null || properties.bind(inputProperties.getValue(key)) != null }

  @Composable
  override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
    val scope = this
    val dataset = JsonObject(dataset(properties) + themeValues())
    val design = properties[designProperty]
    val variant = properties[variantProperty]
    val wiring = wiring(properties)
    val currentWiring by rememberUpdatedState(wiring)

    // The router routes every kind the contract declares to one handler, which sends the action to
    // the agent when it wired it, and otherwise to the app's handler for its kind.
    val liveryHost = remember(host, scope) {
      val kinds = contract.actions.values.map { it.kind }.toSet()
      val route: suspend (RenderPlan, String, Map<String, String>) -> Unit = { plan, action, args ->
        val wired = currentWiring[action]
        val kind = contract.actions[action]?.kind
        when {
          wired != null -> scope.dispatchAction(withArguments(scope, wired, args))
          kind != null && host.appActions[kind] != null -> host.appActions.getValue(kind)(plan, action, args)
          kind != ActionKinds.TRACK -> host.events.emit(
            TemplateEvent(TemplateEvent.Type.Error, host.clock.nowEpochMs(), designId = plan.template.entry.designId, sha256 = plan.template.entry.sha256, action = action, detail = "no handler for $kind"),
          )
        }
      }
      LiveryHost(
        repository = host.repository,
        actions = ActionRouter(kinds.associateWith { route }, host.urlAllowlist, host.events, host.clock),
        events = host.events,
        clock = host.clock,
        images = host.images,
        theme = host.theme,
      )
    }

    BoxWithConstraints(modifier) {
      val widthDp = if (constraints.hasBoundedWidth) maxWidth.value.toInt() else null
      val query = TemplateQuery(contract.ref, host.capabilities, widthDp = widthDp, designId = design, variant = variant, minor = contract.minor)
      val revision by host.repository.revision.collectAsState()
      // Kept across updates: a new dataset replans, and LiveryTemplate pushes the new values into
      // the document already playing instead of starting it again.
      var result by remember { mutableStateOf<PlanResult?>(null) }
      LaunchedEffect(query, dataset, revision) { result = host.planner.forAppData(query, dataset) }
      when (val r = result) {
        null -> {}
        is PlanResult.Ready -> LiveryTemplate(r.plan, liveryHost)
        is PlanResult.Fallback -> {
          // Tell the agent, so it can correct itself; the app decides what draws instead.
          SideEffect { scope.reportError(A2uiException.A2uiRuntimeException("$name (${contract.ref}): ${r.reason}")) }
          host.fallback(contract, r.reason)
        }
      }
    }
  }

  /** The agent's inputs and lists, resolved against the data model, as a dataset for the contract. */
  @Composable
  private fun A2uiComponentScope.dataset(properties: A2uiComponentProperties): JsonObject {
    val values = LinkedHashMap<String, JsonElement>()
    for ((key, spec) in agentInputs) {
      val value: Any? = properties.bind(inputProperties.getValue(key))
      if (value != null) values[key] = toJson(value, spec.type)
    }
    for ((key, property) in listProperties) {
      val value: Any? = properties.bind(property)
      if (value != null) values[key] = toJson(value)
    }
    return JsonObject(values)
  }

  /** Theme role inputs, from the host's Material colour scheme. */
  @Composable
  private fun themeValues(): JsonObject {
    val scheme = MaterialTheme.colorScheme
    val byRole: Map<String, androidx.compose.ui.graphics.Color> = mapOf(
      "primary" to scheme.primary, "onPrimary" to scheme.onPrimary,
      "secondary" to scheme.secondary, "onSecondary" to scheme.onSecondary,
      "surface" to scheme.surface, "onSurface" to scheme.onSurface,
      "surfaceVariant" to scheme.surfaceVariant, "onSurfaceVariant" to scheme.onSurfaceVariant,
      "background" to scheme.background, "onBackground" to scheme.onBackground,
      "error" to scheme.error, "onError" to scheme.onError,
    )
    val values = contract.inputs.mapNotNull { (key, spec) ->
      val colour = spec.role?.let(byRole::get)?.takeIf { spec.type == ValueType.Color } ?: return@mapNotNull null
      key to JsonPrimitive("#" + colour.toArgb().toUInt().toString(16).padStart(8, '0').uppercase())
    }
    return JsonObject(values.toMap())
  }

  private fun wiring(properties: A2uiComponentProperties): Map<String, Map<String, Any?>> {
    val actions = properties[actionsProperty] ?: return emptyMap()
    return actionProperties.mapNotNull { (key, property) -> actions[property]?.let { key to it } }.toMap()
  }

  /**
   * The agent's action with the template's declared arguments added to an event's context. A
   * context key that names an argument too is the agent's mistake: the argument wins, and the agent
   * is told.
   */
  private fun withArguments(scope: A2uiComponentScope, action: Map<String, Any?>, args: Map<String, String>): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    val event = action["event"] as? Map<String, Any?> ?: return action
    @Suppress("UNCHECKED_CAST")
    val context = event["context"] as? Map<String, Any?> ?: emptyMap()
    val shadowed = context.keys intersect args.keys
    if (shadowed.isNotEmpty()) {
      scope.reportError(A2uiException.A2uiRuntimeException("$name: the event context names the template's own arguments $shadowed"))
    }
    return action + ("event" to (event + ("context" to (context + args))))
  }

  companion object {
    const val DESIGN: String = "design"
    const val VARIANT: String = "variant"
    const val ACTIONS: String = "actions"

    @Suppress("UNCHECKED_CAST")
    private fun inputProperty(key: String, spec: InputSpec): DynamicA2uiProperty<Any> {
      // Required to the agent only when the template has no default to draw instead.
      val required = spec.required && spec.default == null
      val description = describe(spec)
      val property: DynamicA2uiProperty<*> = when (spec.type) {
        ValueType.String, ValueType.Color, ValueType.Image -> A2uiProperty.dynamicString(key, required, description)
        ValueType.Int, ValueType.Long, ValueType.Float -> A2uiProperty.dynamicNumber(key, required, description)
        ValueType.Bool -> A2uiProperty.dynamicBoolean(key, required, description)
      }
      // Bound as Any: the value goes into a JSON dataset, where ContractBinder checks its type.
      return property as DynamicA2uiProperty<Any>
    }

    private fun describe(spec: InputSpec): String = listOfNotNull(
      spec.description,
      spec.maxLength?.let { "At most $it characters." },
      spec.min?.let { "At least $it." },
      spec.max?.let { "At most $it." },
      when (spec.type) {
        ValueType.Color -> "A colour, #RRGGBB or #AARRGGBB."
        ValueType.Image -> "An image: asset:<sha256>, https://… or app:<key>."
        ValueType.Int, ValueType.Long -> "A whole number."
        else -> null
      },
      spec.default?.let { "Default $it." },
    ).joinToString(" ").ifEmpty { spec.type.name.lowercase() }

    private fun toJson(value: Any, type: ValueType): JsonElement = when {
      // A2UI numbers arrive as Double; a whole number binds to an int or long input (PROTOCOL.md §4.1).
      (type == ValueType.Int || type == ValueType.Long) && value is Number && value.toDouble() % 1.0 == 0.0 -> JsonPrimitive(value.toLong())
      else -> toJson(value)
    }

    internal fun toJson(value: Any?): JsonElement = when (value) {
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
  }
}
