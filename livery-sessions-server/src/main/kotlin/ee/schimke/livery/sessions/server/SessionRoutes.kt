package ee.schimke.livery.sessions.server

import ee.schimke.livery.sessions.SessionLinks
import ee.schimke.livery.sessions.SessionRelay
import ee.schimke.livery.sessions.server.SessionMcp.Companion.json
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The session server's HTTP routes (SESSIONS.md, Wire format), under [prefix]:
 * - the person's app, with its owner capability as a bearer, from any origin: it long-polls
 *   `GET session?since=<version>` and posts comments, renders and decisions, and sets up links;
 * - agents at `mcp` (MCP, configured once with no header) or `join` (a code for its capability).
 *   Agents are never served to a browser page: a request with an `Origin` is refused (MCP's
 *   DNS-rebinding rule).
 *
 * Sessions are started by the app's own routes, which create them in a kind's store and call
 * [SessionServer.start].
 */
fun Route.sessionRoutes(server: SessionServer, prefix: String = "/api/agent") {
  val relay = server.relay
  val mcp = SessionMcp(server)
  val ownerPaths =
    listOf("links", "link", "link/code", "link/claim", "session", "session/join-code", "session/comment", "session/render", "session/decision", "claim")
  for (path in ownerPaths) {
    options("$prefix/$path") {
      call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
      call.response.header(HttpHeaders.AccessControlAllowMethods, "GET, POST, DELETE, OPTIONS")
      call.response.header(HttpHeaders.AccessControlAllowHeaders, "Authorization, Content-Type")
      call.response.header(HttpHeaders.AccessControlMaxAge, "600")
      call.respondText("", status = HttpStatusCode.NoContent)
    }
  }

  get("$prefix/session") {
    call.ownerHeaders()
    val since = call.request.queryParameters["since"]?.toLongOrNull()
    val wait = call.request.queryParameters["wait"]?.toLongOrNull()?.times(1000) ?: relay.maxWaitMs
    val state = relay.ownerState(call.bearerToken(), since, wait) ?: return@get call.gone("Session unavailable", HttpStatusCode.NotFound)
    call.respondText(state.json().toString(), ContentType.Application.Json)
  }
  post("$prefix/session/join-code") {
    call.ownerHeaders()
    val code = relay.newJoinCode(call.bearerToken()) ?: return@post call.gone("Session unavailable", HttpStatusCode.NotFound)
    call.respondText(buildJsonObject { put("joinCode", code) }.toString(), ContentType.Application.Json)
  }
  post("$prefix/session/comment") {
    call.ownerHeaders()
    val input = call.body() ?: return@post
    val text = (input["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val target = (input["target"] as? JsonPrimitive)?.contentOrNull
    try {
      if (!relay.comment(call.bearerToken(), text, target)) return@post call.gone("Session unavailable", HttpStatusCode.NotFound)
    } catch (_: IllegalArgumentException) {
      return@post call.respondText("Invalid comment", status = HttpStatusCode.BadRequest)
    }
    call.respondText("Comment sent")
  }
  post("$prefix/session/render") {
    call.ownerHeaders()
    val input = call.body(limit = 2_100_000) ?: return@post
    val png = runCatching { java.util.Base64.getDecoder().decode((input["png"] as? JsonPrimitive)?.contentOrNull.orEmpty()) }.getOrNull()
    val revision = (input["revision"] as? JsonPrimitive)?.longOrNull ?: 0
    try {
      if (png == null || !relay.putRender(call.bearerToken(), png, revision)) return@post call.gone("Session unavailable", HttpStatusCode.NotFound)
    } catch (_: IllegalArgumentException) {
      return@post call.respondText("Expected a PNG of at most 1.5 MB", status = HttpStatusCode.BadRequest)
    }
    call.respondText("Render shared")
  }
  post("$prefix/session/decision") {
    call.ownerHeaders()
    val input = call.body() ?: return@post
    val decision = (input["decision"] as? JsonPrimitive)?.contentOrNull
    if (decision != SessionRelay.ACCEPTED && decision != SessionRelay.REJECTED)
      return@post call.respondText("Invalid decision", status = HttpStatusCode.BadRequest)
    try {
      if (!relay.decide(call.bearerToken(), decision == SessionRelay.ACCEPTED, (input["text"] as? JsonPrimitive)?.contentOrNull))
        return@post call.gone("Session unavailable", HttpStatusCode.NotFound)
    } catch (_: IllegalArgumentException) {
      return@post call.respondText("Invalid decision", status = HttpStatusCode.BadRequest)
    }
    call.respondText("Decision sent")
  }
  post("$prefix/claim") {
    call.ownerHeaders()
    val input = call.body() ?: return@post
    call.claimed(relay.claim(call.bearerToken(), (input["code"] as? JsonPrimitive)?.contentOrNull.orEmpty()), "Session unavailable")
  }
  delete("$prefix/session") {
    call.ownerHeaders()
    if (!relay.revoke(call.bearerToken())) return@delete call.gone("Session unavailable", HttpStatusCode.NotFound)
    call.respondText("Session revoked")
  }

  // ---- The person's agent link: set up once, then requests go to that agent ----

  post("$prefix/links") {
    call.ownerHeaders()
    val created =
      try {
        relay.links.create()
      } catch (_: IllegalStateException) {
        return@post call.respondText("Too many agent links", status = HttpStatusCode.TooManyRequests)
      }
    call.respondText(
      buildJsonObject {
        put("link", created.owner)
        put("code", created.code)
      }
        .toString(),
      ContentType.Application.Json,
    )
  }
  get("$prefix/link") {
    call.ownerHeaders()
    val since = call.request.queryParameters["since"]?.toLongOrNull()
    val state = relay.links.state(call.bearerToken(), since) ?: return@get call.gone("Link unavailable", HttpStatusCode.NotFound)
    call.respondText(state.json().toString(), ContentType.Application.Json)
  }
  post("$prefix/link/code") {
    call.ownerHeaders()
    val code = relay.links.newCode(call.bearerToken()) ?: return@post call.gone("Link unavailable", HttpStatusCode.NotFound)
    call.respondText(buildJsonObject { put("code", code) }.toString(), ContentType.Application.Json)
  }
  post("$prefix/link/claim") {
    call.ownerHeaders()
    val input = call.body() ?: return@post
    call.claimed(relay.claimLink(call.bearerToken(), (input["code"] as? JsonPrimitive)?.contentOrNull.orEmpty()), "Link unavailable")
  }
  delete("$prefix/link") {
    call.ownerHeaders()
    if (!relay.links.revoke(call.bearerToken())) return@delete call.gone("Link unavailable", HttpStatusCode.NotFound)
    call.respondText("Agent disconnected")
  }

  // ---- Agents: never from a browser page ----

  post("$prefix/mcp") {
    call.response.header(HttpHeaders.CacheControl, "no-store")
    if (call.request.headers[HttpHeaders.Origin] != null)
      return@post call.respondText("Browser origins are not supported", status = HttpStatusCode.Forbidden)
    val message = call.body(limit = 1_500_000) ?: return@post
    val response = mcp.handle(message, call.bearerToken())
    if (response == null) call.respondText("", status = HttpStatusCode.Accepted)
    else call.respondText(response.toString(), ContentType.Application.Json)
  }
  post("$prefix/join") {
    call.response.header(HttpHeaders.CacheControl, "no-store")
    if (call.request.headers[HttpHeaders.Origin] != null)
      return@post call.respondText("Browser origins are not supported", status = HttpStatusCode.Forbidden)
    val input = call.body() ?: return@post
    val joined =
      relay.join((input["code"] as? JsonPrimitive)?.contentOrNull.orEmpty(), (input["agentName"] as? JsonPrimitive)?.contentOrNull)
        ?: return@post call.respondText("Unknown or expired code", status = HttpStatusCode.NotFound)
    call.respondText(
      buildJsonObject {
          put("session", joined.session)
          put("kind", joined.kind)
          put("title", joined.title)
          put("mcp", "$prefix/mcp")
          server.kinds[joined.kind]?.httpPath?.let { put("http", it) }
          put("next", "Prefer the MCP endpoint with session as an argument.")
        }
        .toString(),
      ContentType.Application.Json,
    )
  }
}

/** The person's view of a session, as `GET session` answers it. */
fun SessionRelay.OwnerState.json(): JsonObject = buildJsonObject {
  put("version", version)
  put("kind", kind)
  put("title", title)
  put("revision", revision)
  put("expiresInSeconds", expiresInSeconds)
  joinCode?.let {
    put("joinCode", it)
    put("joinCodeExpiresInSeconds", joinCodeExpiresInSeconds)
  }
  putJsonObject("agent") {
    put("joined", agent.joined)
    put("connected", agent.connected)
    put("name", agent.name)
    put("status", agent.status)
    agent.lastSeenMs?.let { put("lastSeenSecondsAgo", it / 1000) }
  }
  put("messages", messages.json())
  decision?.let { put("decision", it) }
  if (linked) put("linked", true)
}

/** The person's view of their link, as `GET link` answers it. */
fun SessionLinks.State.json(): JsonObject = buildJsonObject {
  put("version", version)
  put("connected", connected)
  put("name", name)
  put("listening", listening)
  lastSeenMs?.let { put("lastSeenSecondsAgo", it / 1000) }
  code?.let {
    put("code", it)
    put("codeExpiresInSeconds", codeExpiresInSeconds)
  }
}

/** The bearer token of a request, empty when there is none. */
fun ApplicationCall.bearerToken(): String =
  request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim().orEmpty()

private fun ApplicationCall.ownerHeaders() {
  response.header(HttpHeaders.AccessControlAllowOrigin, "*")
  response.header(HttpHeaders.CacheControl, "no-store")
}

private suspend fun ApplicationCall.gone(text: String, status: HttpStatusCode) = respondText(text, status = status)

private suspend fun ApplicationCall.claimed(result: SessionRelay.ClaimResult, gone: String) =
  when (result) {
    SessionRelay.ClaimResult.Claimed -> respondText("Agent connected")
    SessionRelay.ClaimResult.Unknown -> respondText("Unknown or expired code", status = HttpStatusCode.NotFound)
    SessionRelay.ClaimResult.Throttled -> respondText("Too many wrong codes; wait a minute", status = HttpStatusCode.TooManyRequests)
    SessionRelay.ClaimResult.Gone -> respondText(gone, status = HttpStatusCode.Gone)
  }

/**
 * The request body as JSON, read to at most [limit] bytes: a longer one is refused before more of
 * it is buffered, whatever sits upstream, since `mcp` and `join` take requests before any
 * capability is checked.
 */
private suspend fun ApplicationCall.body(limit: Int = 16_000): JsonObject? =
  try {
    request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { require(it <= limit) }
    val bytes = receiveChannel().readRemaining(limit + 1L).readByteArray()
    require(bytes.size <= limit)
    Json.parseToJsonElement(bytes.decodeToString()).jsonObject
  } catch (e: Exception) {
    if (e is CancellationException) throw e
    respondText("Invalid request", status = HttpStatusCode.BadRequest)
    null
  }
