package ee.schimke.livery.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException

/**
 * The [Fetcher] for HTTP(S) on every Kotlin platform, over Ktor: OkHttp on the JVM and Android,
 * Darwin (`URLSession`) on iOS, `fetch` in the browser ([defaultHttpClient]). Other schemes
 * (`file:`, `asset://`) go to [local].
 */
class KtorFetcher(
  private val client: HttpClient = defaultHttpClient(),
  private val local: Fetcher? = null,
) : Fetcher {
  override suspend fun get(url: String, etag: String?): FetchResult {
    if (!url.startsWith("https://") && !url.startsWith("http://")) {
      return local?.get(url, etag) ?: FetchResult.Failed(null, "no fetcher for $url")
    }
    return try {
      val res = client.get(url) { etag?.let { header(HttpHeaders.IfNoneMatch, it) } }
      val retry = res.headers[HttpHeaders.RetryAfter]?.toLongOrNull()
      when (res.status) {
        HttpStatusCode.OK -> FetchResult.Ok(res.readRawBytes(), res.headers[HttpHeaders.ETag])
        HttpStatusCode.NotModified -> FetchResult.NotModified
        else -> FetchResult.Failed(res.status.value, "HTTP ${res.status.value}", retry)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      FetchResult.Failed(null, e.message ?: e::class.simpleName.orEmpty())
    }
  }
}

/** Timeouts every Livery client uses: a slow catalog host must never stall a refresh for long. */
fun io.ktor.client.HttpClientConfig<*>.liveryDefaults() {
  followRedirects = true // GitHub release downloads redirect to their storage host
  install(HttpTimeout) {
    connectTimeoutMillis = 15_000
    requestTimeoutMillis = 60_000
    socketTimeoutMillis = 30_000
  }
}

/** The platform's Ktor engine with [liveryDefaults]. */
expect fun defaultHttpClient(): HttpClient
