package ee.schimke.livery.uibuilder

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Compose UI Builder's export API: a design compiled to a Remote Compose document
 * (`GET /api/ui-builder/v1/designs/<id>/export.rc`), at a revision or its latest, with the revision
 * it drew (`X-UI-Builder-Revision`).
 */
class UiBuilderClient(
  private val apiUrl: String,
  private val token: String,
  private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build(),
) {
  data class Exported(val bytes: ByteArray, val revision: Long?, val url: String)

  fun export(designId: String, revision: Long? = null): Exported {
    require(DESIGN_ID.matches(designId)) { "bad UI Builder design id '$designId'" }
    val base = "${apiUrl.trimEnd('/')}/api/ui-builder/v1/designs/${enc(designId)}"
    val request = HttpRequest.newBuilder(URI("$base/export.rc?token=${enc(token)}" + (revision?.let { "&revision=$it" } ?: "")))
      .timeout(Duration.ofSeconds(60)).GET().build()
    val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
    check(response.statusCode() in 200..299) {
      "UI Builder could not export $designId (${response.statusCode()}): ${response.body().decodeToString().take(300)}"
    }
    check(response.body().size <= MAX_BYTES) { "UI Builder's export of $designId is over $MAX_BYTES bytes" }
    val drawn = response.headers().firstValue("X-UI-Builder-Revision").map { it.toLongOrNull() }.orElse(null) ?: revision
    return Exported(response.body(), drawn, base)
  }

  private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

  companion object {
    private val DESIGN_ID = Regex("[A-Za-z0-9._-]{1,128}")
    private const val MAX_BYTES = 2 * 1024 * 1024
  }
}
