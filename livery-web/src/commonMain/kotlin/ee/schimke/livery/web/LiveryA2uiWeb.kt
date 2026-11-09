package ee.schimke.livery.web

import ee.schimke.livery.web.core.LiveryWeb
import ee.schimke.livery.a2ui.A2uiTemplate
import ee.schimke.livery.a2ui.A2uiDecision
import ee.schimke.livery.client.Decider
import ee.schimke.livery.client.DecisionContext
import ee.schimke.livery.client.DecisionRequest
import ee.schimke.livery.client.RedirectingFetcher
import ee.schimke.livery.client.CacheOnly
import ee.schimke.livery.instantiate.NamedDocuments
import kotlinx.coroutines.withContext
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
import ee.schimke.livery.instantiate.DocumentAdmission
import ee.schimke.livery.instantiate.TemplateInstantiator
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.ManifestSignature
import ee.schimke.livery.protocol.PlayerCapabilities
import ee.schimke.livery.protocol.Target
import ee.schimke.livery.protocol.TemplateEntry
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
  /** Signed document fetcher and optional app-configured catalog host policy. */
  fetcher: RedirectingFetcher? = null,
  documentHosts: ((String) -> Boolean)? = null,
  documentFetcher: RedirectingFetcher? = fetcher,
  private val decider: Decider? = null,
  private val decisionContext: (() -> DecisionContext)? = null,
) {
  /** The size and theme a render is for. */
  /**
   * The space A2UI gives the component, and [decisionId]: what its events carry, by convention
   * `a2ui:<surfaceId>/<componentId>` (docs/A2UI.md), so analytics can tell agent-placed templates
   * from the app's own.
   */
  data class Space(val widthDp: Int? = null, val heightDp: Int? = null, val density: Float = 2f, val themeArgb: Map<String, Long> = emptyMap(), val decisionId: String? = null, val theme: String? = null)

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
      /** A query is still loading; draw this automatic design, then call fetchPending and render again. */
      val pending: Boolean = false,
      /** Impression identity: a server decision can change while [documentKey] stays the same. */
      val presentationKey: String = documentKey,
      val minHeightDp: Int? = null,
    ) : Rendered

    /** Draw the page's fallback, and tell the agent [reason]. */
    data class Fallback(val reason: String, val pending: Boolean = false) : Rendered

    /** An image isn't on the device yet ([reason]): call [fetchImages], then render again. Not the agent's problem. */
    data class Waiting(val reason: String, val pending: Boolean = false) : Rendered
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

  private val named = NamedDocuments(repository, documents, documentFetcher, documentHosts)
  private data class NamedKey(val template: A2uiTemplate.Named, val query: TemplateQuery)
  private data class NamedAnswer(val template: LoadedTemplate?, val retryAtEpochMs: Long = Long.MAX_VALUE)
  private data class QueryKey(val component: String?, val request: DecisionRequest)
  private val namedAnswers = LinkedHashMap<NamedKey, NamedAnswer>()
  private val pendingNames = LinkedHashMap<NamedKey, Contract>()
  private val queries = LinkedHashMap<QueryKey, A2uiDecision>()
  private val pendingQueries = LinkedHashSet<A2uiDecision>()

  private fun hasPending() = pendingNames.isNotEmpty() || pendingQueries.isNotEmpty()
  private fun <K, V> trim(map: MutableMap<K, V>) { while (map.size > 128) map.remove(map.keys.first()) }

  /** Fetches queued documents/decisions off the render path; render again after completion. */
  suspend fun fetchPending(): Boolean {
    val names = pendingNames.toMap()
    pendingNames.clear()
    val asked = pendingQueries.toList()
    pendingQueries.clear()
    var documentsOk = true
    for ((key, contract) in names) {
      val t = key.template
      val loaded = named.load(t.sha256, t.url, t.signature, contract, key.query)
      documentsOk = documentsOk && loaded != null
      // Catalog documents live in the repository, signed standalone ones in this bounded session cache.
      if (loaded == null) namedAnswers[key] = NamedAnswer(null, clock.nowEpochMs() + NAMED_RETRY_MS)
      else if (loaded.design == null) namedAnswers[key] = NamedAnswer(loaded)
      trim(namedAnswers)
    }
    for (answer in asked) decider?.let { answer.fetch(it, repository, clock) }
    return (names.isNotEmpty() || asked.isNotEmpty()) && documentsOk
  }

  private val core = LiveryWeb(repository, planner, capabilities, events, clock, images, urlAllowlist, appKinds)
  private val wiringByRender = mutableMapOf<Int, Map<String, JsonObject>>()
  private fun Space.core() = LiveryWeb.Space(widthDp, heightDp, density, themeArgb, decisionId, theme)

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
      is Rendered.Waiting -> return p
      else -> return Rendered.Fallback("$name (${contract.ref}): $p", hasPending())
    }
    return when (val r = core.prepare(plan, space.core(), wiring.keys)) {
      is LiveryWeb.Rendered.Ready -> {
        wiringByRender[r.id] = wiring
        // A new server decision is a new presentation even when it reuses the same document.
        val presentationKey = JsonArray(listOf(JsonPrimitive(r.documentKey), plan.decision?.let { JsonPrimitive(it.decisionId) } ?: JsonNull)).toString()
        Rendered.Ready(r.id, r.document, r.documentKey, r.values, r.designId, r.sha256, r.heightDp,
          hasPending(), presentationKey, minHeightDp = plan.template.entry.target.size.minHeightDp)
      }
      is LiveryWeb.Rendered.Waiting -> Rendered.Waiting(r.reason, hasPending())
      is LiveryWeb.Rendered.Fallback -> Rendered.Fallback(r.reason, hasPending())
    }
  }

  /** Fetches named documents, decisions and images off the render path. */
  suspend fun fetchImages(): Boolean {
    val pending = hasPending()
    val documentsOk = if (pending) fetchPending() else true
    val hadImages = core.hasPendingImages
    val imagesOk = if (hadImages) core.fetchImages() else true
    return (pending || hadImages) && documentsOk && imagesOk
  }

  fun shown(id: Int) = core.shown(id)
  fun release(id: Int) { core.release(id); wiringByRender.remove(id) }
  fun close() {
    core.close(); wiringByRender.clear(); namedAnswers.clear(); pendingNames.clear()
    queries.clear(); pendingQueries.clear()
  }

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
    val query = TemplateQuery(
      contract.ref, capabilities, widthDp = space.widthDp, heightDp = space.heightDp, theme = space.theme,
      designId = props.str(LiveryA2ui.DESIGN), variant = props.str(LiveryA2ui.VARIANT), minor = contract.minor,
    )
    if (LiveryA2ui.TEMPLATE in props) {
      when (val t = A2uiTemplate.parse(props.getValue(LiveryA2ui.TEMPLATE))) {
        is A2uiTemplate.Invalid -> return t.reason
        is A2uiTemplate.Inline -> return inline(contract, props.getValue(LiveryA2ui.TEMPLATE), dataset, space.decisionId)
        is A2uiTemplate.Named -> {
          val key = NamedKey(t, query)
          // Catalog plans are checked again on each render, including revocations and eligibility.
          val cacheOnly = CacheOnly()
          val local = withContext(cacheOnly) { named.load(t.sha256, t.url, t.signature, contract, query, network = false) }
          val answer = namedAnswers[key]?.takeUnless { it.template == null && clock.nowEpochMs() >= it.retryAtEpochMs }
          if (answer == null) namedAnswers.remove(key)
          val loaded = local ?: answer?.template?.takeUnless { repository.current()?.templates?.any { entry -> entry.sha256 == t.sha256 } == true }
          if (loaded != null) return when (val b = ContractBinder.bind(contract, dataset)) {
            is BindResult.Ok -> RenderPlan(loaded, contract, b.bindings, decisionId = space.decisionId)
            is BindResult.Invalid -> "invalid data: " + b.problems.joinToString { "${it.name}: ${it.message}" }
          }
          if (answer != null) return "named document unavailable or refused"
          if (t.signature == null && cacheOnly.misses == 0) return "${t.sha256} is in no trusted catalog for ${contract.ref}"
          if (t.signature != null && !named.allows(t.url ?: repository.documentUrl(t.sha256))) return "named document URL is not on a host the page's catalog uses"
          pendingNames[key] = contract
          return Rendered.Waiting("fetching ${t.sha256}", pending = true)
        }
        is A2uiTemplate.Query -> {
          val context = decisionContext?.invoke()?.copy(nowEpochMs = 0)
          if (decider != null && context != null) {
            val key = QueryKey(space.decisionId, DecisionRequest(query, t.params, context))
            val answer = queries[key]?.takeUnless { it.expired(clock.nowEpochMs()) }
              ?: A2uiDecision(key.request).also { queries[key] = it; trim(queries) }
            if (!answer.completed) pendingQueries += answer
            answer.decision?.let { d ->
              val themed = d.copy(data = JsonObject(d.data + LiveryA2ui.themeValues(contract, space.themeArgb)))
              val chosen = withContext(CacheOnly()) { planner.forDecision(query, themed, decisionId = space.decisionId ?: d.decisionId) }
              if (chosen is PlanResult.Ready) return chosen.plan
            }
          }
          return when (val auto = planner.forAppData(query.copy(designId = null), dataset, decisionId = space.decisionId)) {
            is PlanResult.Ready -> auto.plan
            is PlanResult.Fallback -> auto.reason
          }
        }
      }
    }
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
    val t = template as? JsonObject ?: return "template must be {\"inline\": <base64>, \"signature\"?} or {\"sha256\", \"url\"?, \"signature\"?}"
    val text = t.str("inline") ?: return "template needs inline: the document as base64"
    val bytes = runCatching { Base64.decode(text) }.getOrNull() ?: return "inline is not base64"
    val signature = when (val s = signatureOf(t)) {
      is String -> return s
      else -> s as ManifestSignature?
    }
    return admitted(contract, bytes, signature, dataset, decisionId)
  }

  /** `{"signature": {keyId, signature, alg?}}`'s signature, null when there is none, or why it is malformed. */
  private fun signatureOf(t: JsonObject): Any? {
    if ("signature" !in t) return null
    val s = t["signature"] as? JsonObject ?: return "signature must be {\"keyId\", \"signature\", \"alg\"?}"
    val alg = if ("alg" in s) s.str("alg") ?: return "signature alg must be a string" else "Ed25519"
    val keyId = s.str("keyId")
    val value = s.str("signature")
    if (keyId == null || value == null) return "signature needs keyId and signature"
    return ManifestSignature(alg = alg, keyId = keyId, signature = value)
  }

  /** [bytes] through [DocumentAdmission], then bound: the agent's own document, as it was sent. */
  private suspend fun admitted(contract: Contract, bytes: ByteArray, signature: ManifestSignature?, dataset: JsonObject, decisionId: String?, expected: String? = null): Any {
    val admission = documents?.admit(bytes, contract, signature) ?: return "the page accepts no documents sent with a message"
    val admitted = when (admission) {
      is Admission.Refused -> return admission.reason
      is Admission.Admitted -> admission
    }
    if (expected != null && admission.sha256 != expected) return "the document fetched is not $expected"
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

  companion object {
    private const val NAMED_RETRY_MS = 30_000L
    /** `https://host[:port]/…`'s host, lowercased. */
    fun hostOf(url: String): String? =
      Regex("^https://([^/:?#@]+)(:[0-9]+)?([/?#]|$)").find(url)?.groupValues?.get(1)?.lowercase()
  }
}
