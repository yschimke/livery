package ee.schimke.livery.a2ui.compose

import androidx.a2ui.compose.runtime.A2uiComponentProperties
import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.runtime.A2uiProperty
import androidx.a2ui.compose.runtime.DynamicA2uiProperty
import androidx.a2ui.compose.runtime.StaticA2uiProperty
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.a2ui.model.protocol.A2uiException
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.produceState
import ee.schimke.livery.client.BindResult
import ee.schimke.livery.client.ContractBinder
import ee.schimke.livery.client.LoadedTemplate
import ee.schimke.livery.instantiate.Admission
import ee.schimke.livery.protocol.ManifestSignature
import ee.schimke.livery.protocol.Target
import ee.schimke.livery.protocol.TemplateEntry
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
import ee.schimke.livery.a2ui.A2uiComponentSpec
import ee.schimke.livery.a2ui.A2uiPropertySpec
import ee.schimke.livery.a2ui.LiveryA2ui
import ee.schimke.livery.client.ActionGate
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
  override val name: String = LiveryA2ui.componentName(contract),
) : A2uiComponent {

  /** What the contract is in A2UI's terms: the one projection every renderer shares (livery-a2ui). */
  val spec: A2uiComponentSpec = LiveryA2ui.component(contract, name)

  override val description: String = spec.description

  private val agentInputs: Map<String, InputSpec> = LiveryA2ui.agentInputs(contract)

  private fun propertyOf(key: String) = spec.properties.first { it.key == key }

  @Suppress("UNCHECKED_CAST")
  private fun dynamic(p: A2uiPropertySpec): DynamicA2uiProperty<Any> = when (p.kind) {
    A2uiPropertySpec.Kind.DynamicString -> A2uiProperty.dynamicString(p.key, p.required, p.description)
    A2uiPropertySpec.Kind.DynamicNumber -> A2uiProperty.dynamicNumber(p.key, p.required, p.description)
    A2uiPropertySpec.Kind.DynamicBoolean -> A2uiProperty.dynamicBoolean(p.key, p.required, p.description)
    else -> A2uiProperty.dynamicValue(p.key, p.required, p.description)
  } as DynamicA2uiProperty<Any> // Bound as Any: the value goes into a JSON dataset, where ContractBinder checks its type.

  private val inputProperties: Map<String, DynamicA2uiProperty<Any>> = agentInputs.keys.associateWith { dynamic(propertyOf(it)) }

  private val listProperties: Map<String, DynamicA2uiProperty<Any>> = contract.lists.keys.associateWith { dynamic(propertyOf(it)) }

  private val designProperty: StaticA2uiProperty<String> = propertyOf(DESIGN).let { A2uiProperty.string(it.key, description = it.description) }

  private val variantProperty: StaticA2uiProperty<String> = propertyOf(VARIANT).let { A2uiProperty.string(it.key, description = it.description) }

  private val actionProperties: Map<String, StaticA2uiProperty<Map<String, Any?>>> =
    propertyOf(ACTIONS).properties.associate { it.key to A2uiProperty.action(it.key, description = it.description) }

  private val actionsProperty: StaticA2uiProperty<A2uiComponentProperties> = propertyOf(ACTIONS).let {
    A2uiProperty.nested(it.key, properties = actionProperties.values.toList(), description = it.description, isAdditionalPropertiesAllowed = false)
  }

  private val templateProperty: StaticA2uiProperty<Any> = propertyOf(TEMPLATE).let { A2uiProperty.any(it.key, description = it.description) }

  override val properties: List<A2uiProperty<*>> =
    inputProperties.values + listProperties.values + listOf(designProperty, variantProperty, actionsProperty, templateProperty)

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
    val updaters = updaters(properties)
    val currentUpdaters by rememberUpdatedState(updaters)

    // The router routes every kind the contract declares to one handler. A `set` writes its value
    // back through the input's data-model binding (and then reaches the agent too, if it wired it);
    // any other action goes to the agent when it wired it, and otherwise to the app's handler for
    // its kind.
    val liveryHost = remember(host, scope) {
      val kinds = contract.actions.values.map { it.kind }.toSet()
      val route: suspend (RenderPlan, String, Map<String, String>) -> Unit = { plan, action, all ->
        // The agent sees only the arguments the contract declares, as the gate checked them, never
        // one the template added (which could also overwrite the agent's own context); the app's
        // handlers still get them all, as everywhere else.
        val args = ActionGate.check(plan.contract, action, all).args
        val wired = currentWiring[action]
        val spec = contract.actions[action]
        val kind = spec?.kind
        if (kind == ActionKinds.SET) {
          val input = spec.input
          val update = input?.let(currentUpdaters::get)
          val value = input?.let { contract.inputs[it] }?.let { LiveryA2ui.writable(args[ActionGate.VALUE], it.type) }
          if (update != null && value != null) {
            update(value)
          } else {
            // A literal (or missing) binding has nowhere to write: tell the agent, as A2UI's own
            // inputs degrade to read-only, and Livery's pipeline.
            val why = "$name.$input is not bound to a data-model path, so $action can't write it"
            scope.reportError(A2uiException.A2uiRuntimeException(why))
            host.events.emit(TemplateEvent(TemplateEvent.Type.Error, host.clock.nowEpochMs(), designId = plan.template.entry.designId, sha256 = plan.template.entry.sha256, action = action, detail = why))
          }
          if (wired != null) scope.dispatchAction(withArguments(scope, wired, args))
        } else when {
          wired != null -> scope.dispatchAction(withArguments(scope, wired, args))
          kind != null && host.appActions[kind] != null -> host.appActions.getValue(kind)(plan, action, all)
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

    val inline = inlineDocument(properties)
    if (inline != null) {
      InlineContent(scope, inline, dataset, liveryHost, modifier)
      return
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

  /** A document the agent sent with the message (PROTOCOL.md §3.4), as given: its bytes, its signature. */
  private class InlineDocument(val text: String?, val bytes: ByteArray?, val signature: ManifestSignature?, val problem: String?) {
    /** The document as sent, compared whole: a different document is always admitted afresh. */
    val key: Any = listOf(text, signature, problem)
  }

  /**
   * Null only when the agent sent no `template`: then the catalog chooses. A `template` that is
   * there but malformed is a document request all the same, so it is refused, never answered with a
   * catalog design (PROTOCOL.md §3.5).
   */
  private fun inlineDocument(properties: A2uiComponentProperties): InlineDocument? {
    if (templateProperty !in properties) return null
    val template = properties[templateProperty] as? Map<*, *>
      ?: return InlineDocument(null, null, null, "template must be {\"inline\": <base64>, \"signature\"?}")
    if ("sha256" in template && "inline" !in template) {
      return InlineDocument(null, null, null, "a template named by sha256 isn't supported on Compose yet: send it inline")
    }
    val text = template["inline"] as? String
      ?: return InlineDocument(null, null, null, "template needs inline: the document as base64")
    val bytes = runCatching { kotlin.io.encoding.Base64.decode(text) }.getOrNull()
      ?: return InlineDocument(text, null, null, "inline is not base64")
    // A signature that is there must verify or refuse: never read a malformed one as "unsigned",
    // which an agent-bytes policy might then play (PROTOCOL.md §3.5).
    val sig = if ("signature" !in template) null else {
      val s = template["signature"] as? Map<*, *>
        ?: return InlineDocument(text, null, null, "signature must be {\"keyId\", \"signature\", \"alg\"?}")
      val keyId = s["keyId"] as? String
      val value = s["signature"] as? String
      val alg = if ("alg" in s) s["alg"] as? String ?: return InlineDocument(text, null, null, "signature alg must be a string") else "Ed25519"
      if (keyId == null || value == null) return InlineDocument(text, null, null, "signature needs keyId and signature")
      ManifestSignature(alg = alg, keyId = keyId, signature = value)
    }
    return InlineDocument(text, bytes, sig, null)
  }

  /**
   * Plays exactly the document the agent sent, or nothing (PROTOCOL.md §5.3): it passes the app's
   * [DocumentAdmission] (signature or agent-bytes policy, the contract, risky features) and its data
   * the contract gate; otherwise the agent is told and the app's fallback draws. Never another
   * template in its place, and never kept: it lives only in this surface.
   */
  @Composable
  private fun InlineContent(scope: A2uiComponentScope, inline: InlineDocument, dataset: JsonObject, liveryHost: LiveryHost, modifier: Modifier) {
    // Each answer is kept with the request it answers: while a new document is being admitted, the
    // previous one doesn't play on with the new request's data and actions; nothing draws.
    val answer by produceState<Pair<Any, Admission>?>(null, inline.key, host.documents) {
      value = inline.key to when {
        inline.bytes == null -> Admission.Refused(inline.problem ?: "no document")
        host.documents == null -> Admission.Refused("the app accepts no documents sent with a message")
        else -> host.documents.admit(inline.bytes, contract, inline.signature)
      }
    }
    val admission = answer?.takeIf { it.first == inline.key }?.second
    val plan: Any? = when (val a = admission) {
      null -> null
      is Admission.Refused -> a.reason
      is Admission.Admitted -> when (val b = ContractBinder.bind(contract, dataset)) {
        is BindResult.Invalid -> "invalid data: " + b.problems.joinToString { "${it.name}: ${it.message}" }
        is BindResult.Ok -> {
          // Its events say whose bytes these were, so analytics never mix them with reviewed designs.
          val label = a.signedBy?.let { "a2ui:signed:$it" } ?: "a2ui:unsigned"
          val entry = TemplateEntry(a.sha256, a.bytes.size.toLong(), label, contract.ref, Target(host.capabilities.surface, host.capabilities.maxRcApiLevel))
          RenderPlan(LoadedTemplate(entry, null, a.bytes, LoadedTemplate.Origin.Fresh, contract), contract, b.bindings)
        }
      }
    }
    Box(modifier) {
      when (plan) {
        is RenderPlan -> LiveryTemplate(plan, liveryHost)
        is String -> {
          SideEffect { scope.reportError(A2uiException.A2uiRuntimeException("$name (${contract.ref}): $plan")) }
          host.fallback(contract, plan)
        }
      }
    }
  }

  /** The agent's inputs and lists, resolved against the data model, as a dataset for the contract. */
  @Composable
  private fun A2uiComponentScope.dataset(properties: A2uiComponentProperties): JsonObject {
    val resolved = LinkedHashMap<String, Any?>()
    for ((key, property) in inputProperties + listProperties) resolved[key] = properties.bind(property)
    return LiveryA2ui.dataset(contract, resolved)
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
    return LiveryA2ui.themeValues(contract, byRole.mapValues { it.value.toArgb().toLong() })
  }

  /**
   * The data-model updater of each input a `set` action names: present only where the agent bound
   * that input to a path (`{"path": …}`), which is the only place a value can be written back.
   */
  @Composable
  private fun A2uiComponentScope.updaters(properties: A2uiComponentProperties): Map<String, (Any?) -> Unit> {
    val updaters = LinkedHashMap<String, (Any?) -> Unit>()
    for (input in setInputs) {
      val property = inputProperties[input] ?: continue
      properties.bindUpdater(property)?.let { updaters[input] = it }
    }
    return updaters
  }

  /** The inputs this contract's `set` actions change, in a stable order (composition reads them each time). */
  private val setInputs: List<String> = LiveryA2ui.setInputs(contract)

  private fun wiring(properties: A2uiComponentProperties): Map<String, Map<String, Any?>> {
    val actions = properties[actionsProperty] ?: return emptyMap()
    return actionProperties.mapNotNull { (key, property) -> actions[property]?.let { key to it } }.toMap()
  }

  /** The agent's action with the template's checked arguments in its event context; a shadowed context key is reported. */
  private fun withArguments(scope: A2uiComponentScope, action: Map<String, Any?>, args: Map<String, String>): Map<String, Any?> {
    val payload = LiveryA2ui.eventPayload(action, args)
    if (payload.shadowed.isNotEmpty()) {
      scope.reportError(A2uiException.A2uiRuntimeException("$name: the event context names the template's own arguments ${payload.shadowed}"))
    }
    return payload.action
  }

  companion object {
    const val DESIGN: String = LiveryA2ui.DESIGN
    const val VARIANT: String = LiveryA2ui.VARIANT
    const val ACTIONS: String = LiveryA2ui.ACTIONS
    const val TEMPLATE: String = LiveryA2ui.TEMPLATE
  }
}
