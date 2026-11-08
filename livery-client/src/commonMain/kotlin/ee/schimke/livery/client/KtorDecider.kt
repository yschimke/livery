package ee.schimke.livery.client

import ee.schimke.livery.protocol.Decision
import io.ktor.client.HttpClient
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json

/** Contract decisions from the app-configured endpoint (§8.3), never a URL from agent params. */
class KtorDecider(
  private val endpoint: String,
  client: HttpClient? = null,
  private val maxBytes: Int = 256 * 1024,
) : Decider {
  // Even an injected client must not forward context or params to a redirect destination.
  private val http = client?.config { followRedirects = false } ?: defaultHttpClient()
  private val json = Json { ignoreUnknownKeys = true }

  init {
    require(ImageResolver.remoteHost(endpoint) != null) { "a decision endpoint must be HTTPS without user information" }
    require(maxBytes > 0)
  }

  fun close() = http.close()

  override suspend fun decide(placement: String, context: DecisionContext): Decision? = null

  override suspend fun decide(request: DecisionRequest): Decision? = try {
    http.preparePost(endpoint) {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(DecisionRequest.serializer(), request))
    }.execute { response ->
      if (response.status.value != 200) return@execute null
      if ((response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > maxBytes) return@execute null
      val bytes = response.bodyAsChannel().readRemaining(maxBytes.toLong() + 1).readByteArray()
      if (bytes.size > maxBytes) return@execute null
      json.decodeFromString(Decision.serializer(), bytes.decodeToString())
    }
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    null
  }
}
