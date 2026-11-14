@file:OptIn(ExperimentalJsExport::class, kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.livery.web.core

import okio.ByteString.Companion.toByteString
import ee.schimke.livery.client.defaultHttpClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
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
import ee.schimke.livery.protocol.PlayerCapabilities
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.TemplateEvent
import kotlin.io.encoding.Base64
import kotlin.js.Promise
import kotlinx.coroutines.promise
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

// The standalone JavaScript API `@yschimke/livery-web` calls: a handle per session, JSON strings in and out, and
// Promises for anything that may wait on the network. Everything it decides is LiveryWeb's.

/** `previewCreate`'s configuration: the catalog, who signs it, and what the page allows. */
@Serializable
internal data class WebConfig(
  /** The catalog's base URL: `manifest.json` is fetched from beside it. */
  val catalog: String,
  /** Trusted Ed25519 keys; with none, [unsigned] must be true. */
  val keys: List<Key> = emptyList(),
  /** Accept an unsigned catalog: a development build only. */
  val unsigned: Boolean = false,
  val capabilities: PlayerCapabilities = PlayerCapabilities(Surfaces.IN_APP, maxRcApiLevel = 7, profiles = setOf("androidx"), maxBytes = 256 * 1024),
  /** Hosts images are fetched from directly; any other remote image goes through the catalog's proxy, if it names one. */
  val imageHosts: Set<String> = emptySet(),
  /** URL prefixes an `open-url` action may open. */
  val urlAllowlist: List<String> = emptyList(),
  /** Action kinds the page handles when the agent didn't wire them. */
  val appKinds: Set<String> = emptySet(),
) {
  @Serializable data class Key(val keyId: String, val publicKey: String, val contracts: Set<String> = emptySet())

}

@Serializable
internal data class WebSpace(val widthDp: Int? = null, val heightDp: Int? = null, val density: Float = 2f, val theme: Map<String, String> = emptyMap(), val decisionId: String? = null, val colorScheme: String? = null)

private class Session(val web: LiveryWeb, val repository: TemplateRepository, val events: MutableList<TemplateEvent>, val http: HttpClient) {
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  fun close() { scope.cancel(); web.close(); events.clear(); http.close() }
}

private val sessions = mutableMapOf<Int, Session>()
private var nextSession = 1
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

private fun session(id: Int): Session = sessions[id] ?: error("no Livery session $id")

private fun <T> inSession(id: Int, block: suspend () -> T): Promise<JsAny?> = session(id).scope.promise { block()?.toString()?.toJsString() }

/** Allocates a session before connecting, so the host can cancel its initial fetch. */
@JsExport
fun previewCreate(config: String): Int {
  val c = json.decodeFromString(WebConfig.serializer(), config)
  val keys = c.keys.map { TrustedKey(it.keyId, Base64.decode(it.publicKey).toByteString(), it.contracts) }
  require(keys.isNotEmpty() || c.unsigned) { "a catalog needs a trusted key, or unsigned: true for development" }
  val clock = Clock { kotlin.time.Clock.System.now().toEpochMilliseconds() }
  val events = mutableListOf<TemplateEvent>()
  val sink = ee.schimke.livery.client.EventSink { if (events.size >= 256) events.removeAt(0); events += it }
  val http = defaultHttpClient()
  val fetcher = KtorFetcher(http)
  val repository = TemplateRepository(
    StaticSource(c.catalog), fetcher, WebCrypto, if (keys.isEmpty()) TrustPolicy.AllowUnsigned else TrustPolicy.Signed(keys),
    MemoryBlobStore(), MemoryKeyValueStore(), clock, listOf(c.capabilities),
  )
  val web = LiveryWeb(
    repository, SlotPlanner(repository, sink, clock), c.capabilities, sink, clock,
    ImageResolver(repository, fetcher, WebCrypto, directHosts = c.imageHosts),
    urlAllowlist = { url -> c.urlAllowlist.any(url::startsWith) }, appKinds = c.appKinds,
  )
  val id = nextSession++
  sessions[id] = Session(web, repository, events, http)
  return id
}

/** Initial fetch belongs to the already allocated session and is cancelled by close. */
@JsExport
fun previewConnect(session: Int): Promise<JsAny?> = inSession(session) { session(session).repository.refresh()::class.simpleName }

/** Fetches the catalog again; resolves to what changed (`updated`, `notModified`, a failure). */
@JsExport
fun previewRefresh(session: Int): Promise<JsAny?> = inSession(session) { session(session).repository.refresh(force = true)::class.simpleName }

/**
 * Renders a pinned [contract] with [data] (JSON) in [space] (`{widthDp, heightDp,
 * density, theme: {role: "#AARRGGBB"}}`): `{ok: true, render, document (base64), documentKey,
 * values: {name: value}, design, sha256}` (load the document only when its key changes; otherwise
 * set the values),
 * `{ok: false, reason}`, or `{ok: false, waiting: true, reason}` when an image isn't on the device
 * yet: call [previewFetchImages], then render again.
 */
@JsExport
fun previewRender(session: Int, contract: String, data: String, space: String, designId: String?, sha256: String?): Promise<JsAny?> = inSession(session) {
  val s = json.decodeFromString(WebSpace.serializer(), space)
  val theme = s.theme.mapNotNull { (role, hex) -> argb(hex)?.let { role to it } }.toMap()
  val r = session(session).web.render(contract, json.parseToJsonElement(data).jsonObject, LiveryWeb.Space(s.widthDp, s.heightDp, s.density, theme, s.decisionId, s.colorScheme), designId, sha256)
  when (r) {
    is LiveryWeb.Rendered.Ready -> buildJsonObject {
      put("ok", true); put("render", r.id); put("document", r.document.base64()); put("documentKey", r.documentKey)
      put("values", JsonObject(r.values)); put("design", r.designId); put("sha256", r.sha256)
      r.heightDp?.let { put("heightDp", it) }
    }
    is LiveryWeb.Rendered.Fallback -> buildJsonObject { put("ok", false); put("reason", r.reason) }
    is LiveryWeb.Rendered.Waiting -> buildJsonObject { put("ok", false); put("waiting", true); put("reason", r.reason) }
  }
}

/** Fetches the images renders were waiting for (off the render path); resolves to whether all arrived. */
@JsExport
fun previewFetchImages(session: Int): Promise<JsAny?> = inSession(session) { session(session).web.fetchResources() }

/** The player shows render [render]: its impression. */
@JsExport
fun previewShown(session: Int, render: Int) = session(session).web.shown(render)

/** Render [render] is gone from the page. */
@JsExport
fun previewRelease(session: Int, render: Int) = session(session).web.release(render)

/**
 * A host named action from render [render]'s player (`valueType` and `value` as the player's
 * named action callback has them, the value as text): `{route: "set", input, value}`,
 * `{route: "app", kind, action, args}` or
 * `{route: "dropped", reason}`.
 */
@JsExport
fun previewAction(session: Int, render: Int, name: String, valueType: String, value: String?): Promise<JsAny?> = inSession(session) {
  when (val r = session(session).web.action(render, name, valueType, value)) {
    is LiveryWeb.Routed.Set -> buildJsonObject {
      put("route", "set"); put("input", r.input); put("value", r.value);
    }
    is LiveryWeb.Routed.App -> buildJsonObject {
      put("route", "app"); put("kind", r.kind); put("action", r.action); put("args", JsonObject(r.args.mapValues { JsonPrimitive(it.value) }))
    }
    is LiveryWeb.Routed.Dropped -> buildJsonObject { put("route", "dropped"); put("reason", r.reason) }
  }
}

/** Livery's events since the last call (impressions, actions, fallbacks, errors), as a JSON array: for the page's analytics. */
@JsExport
fun previewEvents(session: Int): String {
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

/** Ends a preview session; old render IDs can no longer dispatch actions. */
@JsExport
fun previewClose(id: Int) { sessions.remove(id)?.close() }
