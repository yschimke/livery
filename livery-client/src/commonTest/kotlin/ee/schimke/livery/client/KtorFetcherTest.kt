package ee.schimke.livery.client

import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
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
  private val local = Fetcher { url, _ -> FetchResult.Ok(url.encodeUtf8(), null) }
  private val fetcher = KtorFetcher(HttpClient(engine) { liveryDefaults() }, local)

  @Test fun okCarriesTheEtagAndRevalidates() = runTest {
    val ok = assertIs<FetchResult.Ok>(fetcher.get("https://cdn.example/manifest.json", null))
    assertEquals("body", ok.bytes.utf8())
    assertEquals("\"v1\"", ok.etag)
    assertEquals(FetchResult.NotModified, fetcher.get("https://cdn.example/manifest.json", ok.etag))
  }

  @Test fun failuresCarryRetryAfter() = runTest {
    val f = assertIs<FetchResult.Failed>(fetcher.get("https://cdn.example/busy", null))
    assertEquals(503, f.status)
    assertEquals(120, f.retryAfterSeconds)
  }

  @Test fun localSchemesGoToTheLocalFetcher() = runTest {
    assertEquals("asset://catalog/manifest.json", assertIs<FetchResult.Ok>(fetcher.get("asset://catalog/manifest.json", null)).bytes.utf8())
  }
}

class KtorRedirectTest {
  private val contacted = mutableListOf<String>()
  private val engine = MockEngine { req ->
    val url = req.url.toString()
    contacted += url
    when {
      url.startsWith("https://trusted.example/hop") -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/final"))
      url.startsWith("https://trusted.example/away") -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://attacker.example/x.png"))
      url.startsWith("https://github.com/") -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://objects.githubusercontent.com/blob"))
      else -> respond("ok", HttpStatusCode.OK)
    }
  }
  private val fetcher = KtorFetcher(HttpClient(engine) { liveryDefaults() })

  @Test fun followsAllowedRedirectsItself() = runTest {
    assertEquals("ok", assertIs<FetchResult.Ok>(fetcher.get("https://trusted.example/hop", null) { true }).bytes.utf8())
    assertEquals(listOf("https://trusted.example/hop", "https://trusted.example/final"), contacted)
  }

  @Test fun aRefusedRedirectIsNeverContacted() = runTest {
    val allowed = { to: String -> ImageResolver.redirectAllowed(null, setOf("trusted.example"), "https://trusted.example/away", to) }
    assertIs<FetchResult.Failed>(fetcher.get("https://trusted.example/away", null, allowRedirect = allowed))
    assertEquals(listOf("https://trusted.example/away"), contacted)
  }

  @Test fun catalogFetchesFollowHttpsRedirects() = runTest {
    assertIs<FetchResult.Ok>(fetcher.get("https://github.com/o/r/releases/latest/download/manifest.json", null))
  }

  @Test fun bodiesOverTheLimitAreRefused() = runTest {
    val big = HttpClient(MockEngine { respond(ByteArray(10_000), HttpStatusCode.OK) }) { liveryDefaults() }
    assertIs<FetchResult.Failed>(KtorFetcher(big).get("https://cdn.example/big.png", null, 1_000) { true })
    assertIs<FetchResult.Ok>(KtorFetcher(big).get("https://cdn.example/big.png", null, 10_000) { true })
    val declared = HttpClient(MockEngine { respond(ByteArray(10), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "50000000")) }) { liveryDefaults() }
    assertIs<FetchResult.Failed>(KtorFetcher(declared).get("https://cdn.example/huge.png", null, 1_000) { true }, "a declared length over the limit is refused before reading")
  }

  @Test fun proxiedRedirectsStayOnTheProxy() {
    val proxy = ee.schimke.livery.protocol.ImageProxy("https://img.example/{width}?u={url}")
    assertEquals(true, ImageResolver.redirectAllowed(proxy, emptySet(), "https://img.example/10?u=x", "https://img.example/cache/1"))
    assertEquals(false, ImageResolver.redirectAllowed(proxy, emptySet(), "https://img.example/10?u=x", "https://cdn.example/1"))
  }
}
