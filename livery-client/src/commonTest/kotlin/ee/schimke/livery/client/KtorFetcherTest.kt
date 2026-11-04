package ee.schimke.livery.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class KtorFetcherTest {
  private val engine = MockEngine { req ->
    when {
      req.headers[HttpHeaders.IfNoneMatch] == "\"v1\"" -> respond("", HttpStatusCode.NotModified)
      req.url.encodedPath.endsWith("busy") -> respond("", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.RetryAfter, "120"))
      else -> respond("body", HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"v1\""))
    }
  }
  private val local = Fetcher { url, _ -> FetchResult.Ok(url.encodeToByteArray(), null) }
  private val fetcher = KtorFetcher(HttpClient(engine) { liveryDefaults() }, local)

  @Test fun okCarriesTheEtagAndRevalidates() = runTest {
    val ok = assertIs<FetchResult.Ok>(fetcher.get("https://cdn.example/manifest.json", null))
    assertEquals("body", ok.bytes.decodeToString())
    assertEquals("\"v1\"", ok.etag)
    assertEquals(FetchResult.NotModified, fetcher.get("https://cdn.example/manifest.json", ok.etag))
  }

  @Test fun failuresCarryRetryAfter() = runTest {
    val f = assertIs<FetchResult.Failed>(fetcher.get("https://cdn.example/busy", null))
    assertEquals(503, f.status)
    assertEquals(120, f.retryAfterSeconds)
  }

  @Test fun localSchemesGoToTheLocalFetcher() = runTest {
    assertEquals("asset://catalog/manifest.json", assertIs<FetchResult.Ok>(fetcher.get("asset://catalog/manifest.json", null)).bytes.decodeToString())
  }
}
