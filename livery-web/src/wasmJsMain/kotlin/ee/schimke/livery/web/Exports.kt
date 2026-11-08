@file:OptIn(ExperimentalJsExport::class, kotlinx.coroutines.DelicateCoroutinesApi::class)

package ee.schimke.livery.web

import ee.schimke.livery.client.Clock
import ee.schimke.livery.client.ImageResolver
import ee.schimke.livery.client.KtorFetcher
import ee.schimke.livery.client.MemoryBlobStore
import ee.schimke.livery.client.MemoryKeyValueStore
import ee.schimke.livery.client.SlotPlanner
import ee.schimke.livery.client.StaticSource
import ee.schimke.livery.client.TemplateRepository
import ee.schimke.livery.client.TrustPolicy
import ee.schimke.livery.client.TrustedKey
import ee.schimke.livery.client.WebCrypto
import ee.schimke.livery.instantiate.AgentBytesPolicy
import ee.schimke.livery.instantiate.DocumentAdmission
import ee.schimke.livery.instantiate.RiskyFeature
import ee.schimke.livery.a2ui.LiveryA2ui
import ee.schimke.livery.protocol.PlayerCapabilities
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.TemplateEvent
import kotlin.io.encoding.Base64
import kotlin.js.Promise
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

// The JavaScript API `@livery/a2ui` calls: a handle per session, JSON strings in and out, and
// Promises for anything that may wait on the network. Everything it decides is LiveryA2uiWeb's.

/** `liveryOpen`'s configuration: the catalog, who signs it, and what the page allows. */
@Serializable
internal data class WebConfig(
  /** The catalog's base URL: `manifest.json` is fetched from beside it. */
  val catalog: String,
  /** Trusted Ed25519 keys; with none, [unsigned] must be true. */
  val keys: List<Key> = emptyList(),
  /** Accept an unsigned catalog: a development build only. */
  val unsigned: Boolean = false,
  /** Persist verified catalog state and blobs in IndexedDB; false uses session memory. */
  val persistentCache: Boolean = true,
  val capabilities: PlayerCapabilities = PlayerCapabilities(Surfaces.IN_APP, maxRcApiLevel = 7, profiles = setOf("androidx")),
  /** Documents an agent sends with a message: refused unless set. */
  val agentBytes: AgentBytes? = null,
  /** Risky features a signed inline document may use (`shaders`, …). */
  val signedFeatures: Set<String> = emptySet(),
  /** Hosts images are fetched from directly; any other remote image goes through the catalog's proxy, if it names one. */
  val imageHosts: Set<String> = emptySet(),
  /** URL prefixes an `open-url` action may open. */
  val urlAllowlist: List<String> = emptyList(),
  /** Action kinds the page handles when the agent didn't wire them. */
  val appKinds: Set<String> = emptySet(),
  val decisionEndpoint: String? = null,
  val decisionContext: ee.schimke.livery.client.DecisionContext? = null,
) {
  @Serializable data class Key(val keyId: String, val publicKey: String, val contracts: Set<String> = emptySet())

  @Serializable data class AgentBytes(val contracts: Set<String>, val maxBytes: Int = 64 * 1024, val allowedKinds: Set<String> = emptySet())
}

@Serializable
internal data class WebSpace(val widthDp: Int? = null, val heightDp: Int? = null, val density: Float = 2f, val theme: Map<String, String> = emptyMap(), val decisionId: String? = null, val colorScheme: String? = null)

private class Session(val web: LiveryA2uiWeb, val repository: TemplateRepository, val events: MutableList<TemplateEvent>)

private val sessions = mutableMapOf<Int, Session>()
private var nextSession = 1
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

private fun session(id: Int): Session = sessions[id] ?: error("no Livery session $id")

private fun <T> async(block: suspend () -> T): Promise<JsAny?> = GlobalScope.promise { block()?.toString()?.toJsString() }

/** Opens a session on a catalog (refreshing it once) and resolves to its handle. */
@JsExport
fun liveryOpen(config: String): Promise<JsAny?> = async {
  val c = json.decodeFromString(WebConfig.serializer(), config)
  val keys = c.keys.map { TrustedKey(it.keyId, Base64.decode(it.publicKey), it.contracts) }
  require(keys.isNotEmpty() || c.unsigned) { "a catalog needs a trusted key, or unsigned: true for development" }
  val clock = Clock { kotlin.time.Clock.System.now().toEpochMilliseconds() }
  val events = mutableListOf<TemplateEvent>()
  val sink = ee.schimke.livery.client.EventSink { events += it }
  val fetcher = KtorFetcher()
  // Trust configuration is part of the namespace: an unsigned cache can never become a signed one.
  val cacheNamespace = WebCrypto.sha256Hex(json.encodeToString(WebConfig.serializer(), c).encodeToByteArray())
  val stores = if (c.persistentCache) BrowserStores.open(cacheNamespace) else null
  val repository = TemplateRepository(
    StaticSource(c.catalog), fetcher, WebCrypto, if (keys.isEmpty()) TrustPolicy.AllowUnsigned else TrustPolicy.Signed(keys),
    stores?.blobs ?: MemoryBlobStore(), stores?.values ?: MemoryKeyValueStore(), clock, listOf(c.capabilities),
  )
  val documents = if (c.agentBytes == null && keys.isEmpty()) null else DocumentAdmission(
    WebCrypto, keys,
    signedFeatures = c.signedFeatures.mapNotNull(RiskyFeature::fromWire).toSet(),
    agentBytes = c.agentBytes?.let { AgentBytesPolicy(it.contracts, it.maxBytes, it.allowedKinds) },
  )
  val web = LiveryA2uiWeb(
    repository, SlotPlanner(repository, sink, clock), c.capabilities, sink, clock,
    ImageResolver(repository, fetcher, WebCrypto, directHosts = c.imageHosts),
    documents, urlAllowlist = { url -> c.urlAllowlist.any(url::startsWith) }, appKinds = c.appKinds,
    documentFetcher = fetcher,
    decider = c.decisionEndpoint?.let { ee.schimke.livery.client.KtorDecider(it) },
    decisionContext = c.decisionContext?.let { context -> { context.copy(nowEpochMs = clock.nowEpochMs()) } },
  )
  suspend fun refresh() {
    repository.refresh()
  }
  // A saved manifest alone is not a warm cache: interrupted prefetch can leave every blob missing.
  // Catalog adapters register components once, so repair downloads before returning an empty set.
  if (web.components().isEmpty()) refresh()
  else GlobalScope.launch {
    try { refresh() }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { /* A storage failure during background refresh must not stop cached rendering. */ }
  }
  val id = nextSession++
  sessions[id] = Session(web, repository, events)
  id
}

/** Fetches the catalog again; resolves to what changed (`updated`, `notModified`, a failure). */
@JsExport
fun liveryRefresh(session: Int): Promise<JsAny?> = async { session(session).repository.refresh(force = true)::class.simpleName }

/**
 * The components to register: a JSON array of `{name, contract, description, schema, properties,
 * setInputs}`, each `properties` entry `{key, kind, required}` in `livery-a2ui`'s terms.
 */
@JsExport
fun liveryComponents(session: Int): Promise<JsAny?> = async {
  buildJsonArray {
    for (spec in session(session).web.components()) add(buildJsonObject {
      put("name", spec.name)
      put("contract", spec.contract.ref)
      put("description", spec.description)
      put("schema", spec.schema())
      put("properties", JsonArray(spec.properties.map { p ->
        buildJsonObject {
          put("key", p.key); put("kind", p.kind.name); put("required", p.required)
          if (p.properties.isNotEmpty()) put("properties", JsonArray(p.properties.map { JsonPrimitive(it.key) }))
        }
      }))
      put("setInputs", JsonArray(LiveryA2ui.setInputs(spec.contract).map(::JsonPrimitive)))
    })
  }
}

/** The A2UI catalog JSON of the session's playable contracts, as `livery build --a2ui` writes it. */
@JsExport
fun liveryCatalogSchema(session: Int, catalogId: String): Promise<JsAny?> = async {
  LiveryA2ui.catalogSchema(catalogId, session(session).web.playable())
}

/**
 * Renders component [name] with its resolved [props] (JSON) in [space] (`{widthDp, heightDp,
 * density, theme: {role: "#AARRGGBB"}}`): `{ok: true, render, document (base64), documentKey,
 * values: {name: value}, design, sha256}` (load the document only when its key changes; otherwise
 * set the values),
 * `{ok: false, reason}`, or `{ok: false, waiting: true, reason}` when an image isn't on the device
 * yet: call [liveryFetchImages], then render again.
 */
@JsExport
fun liveryRender(session: Int, name: String, props: String, space: String): Promise<JsAny?> = async {
  val s = json.decodeFromString(WebSpace.serializer(), space)
  val theme = s.theme.mapNotNull { (role, hex) -> argb(hex)?.let { role to it } }.toMap()
  val r = session(session).web.render(name, json.parseToJsonElement(props).jsonObject, LiveryA2uiWeb.Space(s.widthDp, s.heightDp, s.density, theme, s.decisionId, s.colorScheme))
  when (r) {
    is LiveryA2uiWeb.Rendered.Ready -> buildJsonObject {
      put("ok", true); put("render", r.id); put("document", Base64.encode(r.document)); put("documentKey", r.documentKey)
      put("presentationKey", r.presentationKey)
      put("values", JsonObject(r.values)); put("design", r.designId); put("sha256", r.sha256)
      put("pending", r.pending)
      r.heightDp?.let { put("heightDp", it) }
      r.minHeightDp?.let { put("minHeightDp", it) }
    }
    is LiveryA2uiWeb.Rendered.Fallback -> buildJsonObject { put("ok", false); put("reason", r.reason); put("pending", r.pending) }
    is LiveryA2uiWeb.Rendered.Waiting -> buildJsonObject { put("ok", false); put("waiting", true); put("reason", r.reason); put("pending", r.pending) }
  }
}

/** Fetches the images renders were waiting for (off the render path); resolves to whether all arrived. */
@JsExport
fun liveryFetchImages(session: Int): Promise<JsAny?> = async { session(session).web.fetchImages() }

/** Fetches query answers and named documents off the render path. */
@JsExport
fun liveryFetchPending(session: Int): Promise<JsAny?> = async { session(session).web.fetchPending() }

/** The player shows render [render]: its impression. */
@JsExport
fun liveryShown(session: Int, render: Int) = session(session).web.shown(render)

/** Render [render] is gone from the page. */
@JsExport
fun liveryRelease(session: Int, render: Int) = session(session).web.release(render)

/**
 * A host named action from render [render]'s player (`valueType` and `value` as the player's
 * `cp-rc-host-named-action` message has them, the value as text): `{route: "agent", action,
 * shadowed}`, `{route: "set", input, value, agent?}`, `{route: "app", kind, action, args}` or
 * `{route: "dropped", reason}`.
 */
@JsExport
fun liveryAction(session: Int, render: Int, name: String, valueType: String, value: String?): Promise<JsAny?> = async {
  fun agent(a: LiveryA2uiWeb.Routed.Agent) = buildJsonObject {
    put("route", "agent"); put("action", a.action); put("shadowed", JsonArray(a.shadowed.map(::JsonPrimitive)))
  }
  when (val r = session(session).web.action(render, name, valueType, value)) {
    is LiveryA2uiWeb.Routed.Agent -> agent(r)
    is LiveryA2uiWeb.Routed.Set -> buildJsonObject {
      put("route", "set"); put("input", r.input); put("value", r.value); r.agent?.let { put("agent", agent(it)) }
    }
    is LiveryA2uiWeb.Routed.App -> buildJsonObject {
      put("route", "app"); put("kind", r.kind); put("action", r.action); put("args", JsonObject(r.args.mapValues { JsonPrimitive(it.value) }))
    }
    is LiveryA2uiWeb.Routed.Dropped -> buildJsonObject { put("route", "dropped"); put("reason", r.reason) }
  }
}

/** Livery's events since the last call (impressions, actions, fallbacks, errors), as a JSON array: for the page's analytics. */
@JsExport
fun liveryEvents(session: Int): String {
  val events = session(session).events
  val out = json.encodeToString(ListSerializer(TemplateEvent.serializer()), events.toList())
  events.clear()
  return out
}

/** `#RRGGBB` or `#AARRGGBB` as ARGB. */
private fun argb(hex: String): Long? {
  val h = hex.removePrefix("#")
  val v = h.toLongOrNull(16) ?: return null
  return when (h.length) { 6 -> 0xFF000000L or v; 8 -> v; else -> null }
}
