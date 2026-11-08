package ee.schimke.livery.web

import ee.schimke.livery.web.core.LiveryWeb
import ee.schimke.livery.a2ui.A2uiComponentSpec
import ee.schimke.livery.a2ui.LiveryA2ui
import ee.schimke.livery.client.ActionGate
import ee.schimke.livery.client.BindResult
import ee.schimke.livery.client.Clock
import ee.schimke.livery.client.ContractBinder
import ee.schimke.livery.client.EventSink
import ee.schimke.livery.client.LoadedTemplate
import ee.schimke.livery.client.PlanResult
import ee.schimke.livery.client.RenderPlan
import ee.schimke.livery.client.SlotPlanner
import ee.schimke.livery.client.TemplateQuery
import ee.schimke.livery.client.TemplateRepository
import ee.schimke.livery.client.ImageResolver
import ee.schimke.livery.instantiate.Admission
import ee.schimke.livery.instantiate.CachedImages
import ee.schimke.livery.instantiate.DocumentAdmission
import ee.schimke.livery.instantiate.TemplateInstantiator
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.ManifestSignature
import ee.schimke.livery.protocol.PlayerCapabilities
import ee.schimke.livery.protocol.Target
import ee.schimke.livery.protocol.TemplateEntry
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Livery components in an A2UI surface on a host that plays document bytes: a browser's Remote
 * Compose player (docs/A2UI.md "The web"). The same pipeline as `livery-a2ui-compose`, without
 * Compose: a component's resolved properties become a dataset, the planner selects and binds a
 * template (or [DocumentAdmission] admits the agent's own document), and the page plays it with the
 * bound values as the document's named values. A data-model change is a new [render] with the same
 * [Rendered.Ready.documentKey]: the page pushes the changed values into the document already
 * playing, as `LiveryTemplate` does on Compose, and never reloads it. Only what a web player can't
 * set on a playing document is written into it ([TemplateInstantiator]): the bound images (drawn
 * from `livery:value/<input>`) and `long` values; a change to one of those is a new document.
 *
 * Rendering never waits on the network: [render] uses only images already on the device, and
 * answers [Rendered.Waiting] when one is missing; [fetchImages] fetches them, off the render path,
 * and the page renders again (as widget hosts do with `CachedImages`).
 *
 * A document's host action comes back through [action]: [ActionGate] checks it, then it goes to
 * the agent when it wired it, writes an input back for a `set`, or goes to the page's own handler
 * for its kind ([appKinds]).
 */
class LiveryA2uiWeb(
  val repository: TemplateRepository,
  private val planner: SlotPlanner,
  val capabilities: PlayerCapabilities,
  private val events: EventSink,
  private val clock: Clock,
  /** Images for templates that draw them; null draws none (a template that needs one waits forever). */
  private val images: ImageResolver? = null,
  private val documents: DocumentAdmission? = null,
  private val urlAllowlist: (String) -> Boolean = { false },
  /** Action kinds the page handles itself (`navigate`, `dismiss`, `app:…`) when the agent didn't wire them. */
  private val appKinds: Set<String> = emptySet(),
) {
  /** The size and theme a render is for. */
  /**
   * The space A2UI gives the component, and [decisionId]: what its events carry, by convention
   * `a2ui:<surfaceId>/<componentId>` (docs/A2UI.md), so analytics can tell agent-placed templates
   * from the app's own.
   */
  data class Space(val widthDp: Int? = null, val heightDp: Int? = null, val density: Float = 2f, val themeArgb: Map<String, Long> = emptyMap(), val decisionId: String? = null)

  sealed interface Rendered {
    /**
     * Play [document] with [values] (its named values: strings, numbers, booleans as 1 or 0, colours
     * as `#AARRGGBB`); pass [id] to [action] and [shown]. While [documentKey] is the one already
     * playing, only the values changed: set them, don't load the document again. [heightDp] is
     * the template's own height (docs/A2UI.md, the height rule): what to draw it at when the page
     * gives the component none.
     */
    data class Ready(
      val id: Int,
      val document: ByteArray,
      val documentKey: String,
      val values: Map<String, JsonElement>,
      val designId: String,
      val sha256: String,
      val heightDp: Int? = null,
    ) : Rendered

    /** Draw the page's fallback, and tell the agent [reason]. */
    data class Fallback(val reason: String) : Rendered

    /** An image isn't on the device yet ([reason]): call [fetchImages], then render again. Not the agent's problem. */
    data class Waiting(val reason: String) : Rendered
  }

  /** Where a template's host action goes. */
  sealed interface Routed {
    /** Dispatch [action], the agent's A2UI action with the template's checked arguments in its context. */
    data class Agent(val action: JsonObject, val shadowed: List<String>) : Routed

    /**
     * Write [value] to [input]'s data-model binding (null: the value doesn't fit the input), then
     * dispatch [agent] if the agent wired the action too.
     */
    data class Set(val input: String, val value: JsonElement, val agent: Agent?) : Routed

    /** The page's own handler for [kind], with every argument the template sent. */
    data class App(val kind: String, val action: String, val args: Map<String, String>) : Routed

    /** Not passed on: undeclared, malformed, a URL outside the allowlist, or no handler ([reason]). */
    data class Dropped(val reason: String) : Routed
  }

  private val core = LiveryWeb(repository, planner, capabilities, events, clock, images, urlAllowlist, appKinds)
  private val wiringByRender = mutableMapOf<Int, Map<String, JsonObject>>()
  private fun Space.core() = LiveryWeb.Space(widthDp, heightDp, density, themeArgb, decisionId)

  /**
   * The contracts worth advertising: one per major, those the repository can resolve a template for
   * now, from disk alone (as `LiveryA2uiCompose.playableContracts`).
   */
  suspend fun playable(): List<Contract> {
    val manifest = repository.current() ?: return emptyList()
    return LiveryA2ui.newestMinors(manifest.contracts).filter { repository.resolve(TemplateQuery(it.ref, capabilities)) != null }
  }

  /** The A2UI components of [playable]: what the page registers in its catalog. */
  suspend fun components(): List<A2uiComponentSpec> = playable().map { LiveryA2ui.component(it) }

  /**
   * Renders the component [name] with its resolved [props]: the agent inputs and lists as A2UI
   * resolved them, `design`, `variant`, `template` as given, and `actions` as the agent's raw
   * A2UI actions (by contract action name).
   */
  suspend fun render(name: String, props: JsonObject, space: Space = Space()): Rendered {
    val contract = playable().firstOrNull { LiveryA2ui.componentName(it) == name }
      ?: return Rendered.Fallback("no playable contract for $name")
    val dataset = JsonObject(LiveryA2ui.dataset(contract, props.mapValues { it.value }) + LiveryA2ui.themeValues(contract, space.themeArgb))
    val wiring = (props[LiveryA2ui.ACTIONS] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }.toMap()
    val plan = when (val p = plan(contract, props, dataset, space)) {
      is RenderPlan -> p
      else -> return Rendered.Fallback("$name (${contract.ref}): $p")
    }
    return when (val r = core.prepare(plan, space.core(), wiring.keys)) {
      is LiveryWeb.Rendered.Ready -> { wiringByRender[r.id] = wiring; Rendered.Ready(r.id, r.document, r.documentKey, r.values, r.designId, r.sha256, r.heightDp) }
      is LiveryWeb.Rendered.Waiting -> Rendered.Waiting(r.reason)
      is LiveryWeb.Rendered.Fallback -> Rendered.Fallback(r.reason)
    }
  }

  suspend fun fetchImages(): Boolean = core.fetchImages()
  fun shown(id: Int) = core.shown(id)
  fun release(id: Int) { core.release(id); wiringByRender.remove(id) }
  fun close() { core.close(); wiringByRender.clear() }
  suspend fun action(id: Int, name: String, valueType: String, value: String?): Routed {
    val wiring = wiringByRender[id].orEmpty()
    return when (val r = core.action(id, name, valueType, value)) {
      is LiveryWeb.Routed.Set -> Routed.Set(r.input, r.value, wiring[name]?.let { agent(it, r.checked) })
      is LiveryWeb.Routed.App -> wiring[name]?.let { agent(it, r.checked) } ?: Routed.App(r.kind, r.action, r.args)
      is LiveryWeb.Routed.Dropped -> Routed.Dropped(r.reason)
    }
  }

  private fun agent(action: JsonObject, args: Map<String, String>): Routed.Agent {
    val payload = LiveryA2ui.eventPayload(action.mapValues { it.value }, args)
    return Routed.Agent(LiveryA2ui.toJson(payload.action) as JsonObject, payload.shadowed.toList())
  }

  /** A [RenderPlan], or why there is none. */
  private suspend fun plan(contract: Contract, props: JsonObject, dataset: JsonObject, space: Space): Any {
    if (LiveryA2ui.TEMPLATE in props) return inline(contract, props.getValue(LiveryA2ui.TEMPLATE), dataset, space.decisionId)
    val query = TemplateQuery(
      contract.ref, capabilities, widthDp = space.widthDp,
      designId = props.str(LiveryA2ui.DESIGN), variant = props.str(LiveryA2ui.VARIANT), minor = contract.minor,
    )
    return when (val r = planner.forAppData(query, dataset, decisionId = space.decisionId)) {
      is PlanResult.Ready -> r.plan
      is PlanResult.Fallback -> r.reason
    }
  }

  /**
   * The document the agent sent (PROTOCOL.md §3.4), as `LiveryA2uiComponent` reads it: exactly it
   * or nothing, never a catalog design in its place. A `template` that is there but malformed is
   * refused, and a signature that is there must verify.
   */
  private suspend fun inline(contract: Contract, template: JsonElement, dataset: JsonObject, decisionId: String?): Any {
    val t = template as? JsonObject ?: return "template must be {\"inline\": <base64>, \"signature\"?}"
    val text = t.str("inline") ?: return "template needs inline: the document as base64"
    val bytes = runCatching { Base64.decode(text) }.getOrNull() ?: return "inline is not base64"
    val signature = if ("signature" !in t) null else {
      val s = t["signature"] as? JsonObject ?: return "signature must be {\"keyId\", \"signature\", \"alg\"?}"
      val alg = if ("alg" in s) s.str("alg") ?: return "signature alg must be a string" else "Ed25519"
      val keyId = s.str("keyId")
      val value = s.str("signature")
      if (keyId == null || value == null) return "signature needs keyId and signature"
      ManifestSignature(alg = alg, keyId = keyId, signature = value)
    }
    val admission = documents?.admit(bytes, contract, signature) ?: return "the page accepts no documents sent with a message"
    val admitted = when (admission) {
      is Admission.Refused -> return admission.reason
      is Admission.Admitted -> admission
    }
    val bindings = when (val b = ContractBinder.bind(contract, dataset)) {
      is BindResult.Invalid -> return "invalid data: " + b.problems.joinToString { "${it.name}: ${it.message}" }
      is BindResult.Ok -> b.bindings
    }
    // Its events say whose bytes these were, so analytics never mix them with reviewed designs.
    val label = admitted.signedBy?.let { "a2ui:signed:$it" } ?: "a2ui:unsigned"
    val entry = TemplateEntry(admitted.sha256, admitted.bytes.size.toLong(), label, contract.ref, Target(capabilities.surface, capabilities.maxRcApiLevel))
    return RenderPlan(LoadedTemplate(entry, null, admitted.bytes, LoadedTemplate.Origin.Fresh, contract), contract, bindings, decisionId = decisionId)
  }

  private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
