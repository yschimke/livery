package ee.schimke.livery.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import kotlinx.coroutines.CancellationException

/**
 * The [Fetcher] for HTTP(S) on every Kotlin platform, over Ktor: OkHttp on the JVM and Android,
 * Darwin (`URLSession`) on iOS, `fetch` in the browser ([defaultHttpClient]). Other schemes
 * (`file:`, `asset://`) go to [local].
 */
class KtorFetcher(
  private val client: HttpClient = defaultHttpClient(),
  private val local: Fetcher? = null,
  private val maxRedirects: Int = 5,
) : RedirectingFetcher {
  /** Catalog files: redirects are followed to any `https` host (GitHub release downloads need it). */
  override suspend fun get(url: String, etag: String?): FetchResult = get(url, etag) { it.startsWith("https://") }

  /**
   * Follows up to [maxRedirects] redirects itself (the client never does: [liveryDefaults]), asking
   * [allowRedirect] before each one, so a refused destination is never contacted.
   */
  override suspend fun get(url: String, etag: String?, allowRedirect: (to: String) -> Boolean): FetchResult {
    if (!url.startsWith("https://") && !url.startsWith("http://")) {
      return local?.get(url, etag) ?: FetchResult.Failed(null, "no fetcher for $url")
    }
    var current = url
    repeat(maxRedirects + 1) {
      try {
        val res = client.get(current) { etag?.let { header(HttpHeaders.IfNoneMatch, it) } }
        val retry = res.headers[HttpHeaders.RetryAfter]?.toLongOrNull()
        when (res.status.value) {
          200 -> return FetchResult.Ok(res.readRawBytes(), res.headers[HttpHeaders.ETag])
          304 -> return FetchResult.NotModified
          301, 302, 303, 307, 308 -> {
            val location = res.headers[HttpHeaders.Location] ?: return FetchResult.Failed(res.status.value, "redirect without Location")
            val next = URLBuilder(current).takeFrom(location).buildString()
            if (!allowRedirect(next)) return FetchResult.Failed(res.status.value, "redirect to $next refused")
            current = next
          }
          else -> return FetchResult.Failed(res.status.value, "HTTP ${res.status.value}", retry)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        return FetchResult.Failed(null, e.message ?: e::class.simpleName.orEmpty())
      }
    }
    return FetchResult.Failed(null, "more than $maxRedirects redirects")
  }
}

/** Timeouts every Livery client uses: a slow catalog host must never stall a refresh for long. */
fun io.ktor.client.HttpClientConfig<*>.liveryDefaults() {
  // KtorFetcher follows redirects itself, so each hop can be checked before it is contacted.
  followRedirects = false
  install(HttpTimeout) {
    connectTimeoutMillis = 15_000
    requestTimeoutMillis = 60_000
    socketTimeoutMillis = 30_000
  }
}

/** The platform's Ktor engine with [liveryDefaults]. */
expect fun defaultHttpClient(): HttpClient
