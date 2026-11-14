package ee.schimke.livery.uibuilder

import okio.ByteString.Companion.toByteString
import okio.ByteString
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes

/**
 * Compose UI Builder's export API: a design compiled to a Remote Compose document
 * (`GET /api/ui-builder/v1/designs/<id>/export.rc`), at a revision or its latest, with the revision
 * it drew (`X-UI-Builder-Revision`). On Ktor, so it runs wherever livery-mcp-core does.
 */
class UiBuilderClient(
  private val apiUrl: String,
  private val token: String,
  private val http: HttpClient = defaultClient(),
) {
  data class Exported(val bytes: ByteString, val revision: Long?, val url: String)

  suspend fun export(designId: String, revision: Long? = null): Exported {
    require(DESIGN_ID.matches(designId)) { "bad UI Builder design id '$designId'" }
    // The id is only letters, digits, '.', '_' and '-': nothing to escape in a path.
    val base = "${apiUrl.trimEnd('/')}/api/ui-builder/v1/designs/$designId"
    val response: HttpResponse = http.get("$base/export.rc") {
      parameter("token", token)
      revision?.let { parameter("revision", it) }
    }
    val body = response.readRawBytes()
    check(response.status.value in 200..299) {
      "UI Builder could not export $designId (${response.status.value}): ${body.decodeToString().take(300)}"
    }
    check(body.size <= MAX_BYTES) { "UI Builder's export of $designId is over $MAX_BYTES bytes" }
    val drawn = response.headers["X-UI-Builder-Revision"]?.toLongOrNull() ?: revision
    return Exported(body.toByteString(), drawn, base)
  }

  companion object {
    private val DESIGN_ID = Regex("[A-Za-z0-9._-]{1,128}")
    private const val MAX_BYTES = 2 * 1024 * 1024

    /** The platform's engine, no redirects (the token is in the query), and the old client's timeouts. */
    fun defaultClient(): HttpClient = HttpClient {
      followRedirects = false
      expectSuccess = false
      install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 60_000
      }
    }
  }
}
