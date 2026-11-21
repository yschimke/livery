package ee.schimke.livery.sessions.templates

import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.Target
import ee.schimke.livery.sessions.server.SessionServer
import ee.schimke.livery.sessions.server.bearerToken
import ee.schimke.livery.sessions.server.receiveBoundedJson
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okio.ByteString.Companion.toByteString

/**
 * The person's side of template sessions, beside [ee.schimke.livery.sessions.server.sessionRoutes]
 * under the same [prefix]:
 * - `POST templates` starts one: `{contract, target, title, documentBase64?, link?}` gives back the
 *   owner capability and, unless it went to the person's linked agent, the code for the agent.
 *   [canCreate] decides who may: by default anyone, bounded by the kind's session limit; an app
 *   that signs people in checks them here.
 * - `GET templates/draft` (owner capability as bearer) is the document to play now, with its
 *   revision and sha256 in headers; 204 until there is one.
 */
fun Route.templateSessionRoutes(
  server: SessionServer,
  kind: TemplateKind,
  prefix: String = "/api/agent",
  canCreate: suspend (ApplicationCall) -> Boolean = { true },
) {
  require(server.kinds[kind.id] === kind) { "Register the kind with the server first" }
  for (path in listOf("templates", "templates/draft")) {
    options("$prefix/$path") {
      call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
      call.response.header(HttpHeaders.AccessControlAllowMethods, "GET, POST, OPTIONS")
      call.response.header(HttpHeaders.AccessControlAllowHeaders, "Authorization, Content-Type")
      call.response.header(HttpHeaders.AccessControlExposeHeaders, "$REVISION, $SHA256")
      call.respondText("", status = HttpStatusCode.NoContent)
    }
  }

  post("$prefix/templates") {
    call.ownerHeaders()
    if (!canCreate(call)) return@post call.respondText("Not allowed", status = HttpStatusCode.Forbidden)
    // A contract and a starting document: the document's limit, base64, plus room for the rest.
    val input = call.receiveBoundedJson(limit = (kind.maxBytes / 3 + 1) * 4 + 256 * 1024) ?: return@post
    val parsed =
      runCatching {
          val contract = JSON.decodeFromJsonElement(Contract.serializer(), input.getValue("contract"))
          val target = JSON.decodeFromJsonElement(Target.serializer(), input.getValue("target"))
          val document = (input["documentBase64"] as? JsonPrimitive)?.contentOrNull?.let { Base64.getDecoder().decode(it).toByteString() }
          Triple(contract, target, document)
        }
        .getOrNull() ?: return@post call.respondText("Expected a contract, a target and optionally documentBase64", status = HttpStatusCode.BadRequest)
    val (contract, target, document) = parsed
    val title = (input["title"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty().ifEmpty { contract.title ?: contract.ref }
    val created =
      try {
        kind.create(contract, target, title, document)
      } catch (e: IllegalStateException) {
        return@post call.respondText(e.message ?: "Too many sessions", status = HttpStatusCode.TooManyRequests)
      } catch (e: IllegalArgumentException) {
        return@post call.respondText(e.message ?: "Invalid request", status = HttpStatusCode.BadRequest)
      }
    val link = (input["link"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    val code = server.start(kind.id, created.owner, created.agent, title, link)
    call.respondText(
      buildJsonObject {
          put("owner", created.owner)
          if (code != null) put("joinCode", code) else put("linked", true)
        }
        .toString(),
      ContentType.Application.Json,
    )
  }

  get("$prefix/templates/draft") {
    call.ownerHeaders()
    val draft = kind.draft(call.bearerToken()) ?: return@get call.respondText("Session unavailable", status = HttpStatusCode.NotFound)
    val bytes = draft.bytes ?: return@get call.respondText("", status = HttpStatusCode.NoContent)
    // On the response itself: a browser reads only the headers the actual response exposes.
    call.response.header(HttpHeaders.AccessControlExposeHeaders, "$REVISION, $SHA256")
    call.response.header(REVISION, draft.revision.toString())
    call.response.header(SHA256, draft.sha256.orEmpty())
    call.respondBytes(bytes.toByteArray(), ContentType.Application.OctetStream)
  }
}

private const val REVISION = "X-Livery-Revision"
private const val SHA256 = "X-Livery-Sha256"
private val JSON = Json { ignoreUnknownKeys = true }

private fun ApplicationCall.ownerHeaders() {
  response.header(HttpHeaders.AccessControlAllowOrigin, "*")
  response.header(HttpHeaders.CacheControl, "no-store")
}
