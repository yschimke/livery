package ee.schimke.livery.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** No Compose Preview Server and deliberately no AutoHeadResponse plugin. */
class ComponentPublicationsTest {
  private val fixture = PublicationFixture()

  @AfterTest fun cleanup() = fixture.close()

  private fun ApplicationTestBuilder.mount(viewer: Boolean = true) {
    application {
      routing {
        ComponentPublications(
          store = PublicationStore(fixture.root),
          viewerRoot = fixture.viewer.takeIf { viewer },
          publicOrigin = "https://preview.example",
          viewerConfig = """{"keys":[{"keyId":"publisher","publicKey":"public-test-key"}]}""",
        ).install(this)
      }
    }
  }

  @Test fun `page exposes escaped static metadata discovery and operator viewer config`() = testApplication {
    fixture.bundle(title = "<script>alert(1)</script> & \"quoted\"")
    mount()
    val response = client.fetch("/p/quantity/r/one/")
    assertEquals(HttpStatusCode.OK, response.status)
    val html = response.bodyAsText()
    assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
    assertFalse(html.contains("<script>alert(1)</script>"))
    assertTrue(html.contains("&amp;"))
    assertTrue(html.contains("&quot;quoted&quot;"))
    assertTrue(html.contains("application/vnd.livery.preview+json"))
    assertTrue(response.headers[HttpHeaders.Link]!!.contains("/p/quantity/r/one/preview.json"))
    assertTrue(html.contains("https://preview.example/p/quantity/r/one/thumbnail.png"))
    assertTrue(html.contains("https://preview.example/p/quantity/r/one/"))
    assertTrue(html.contains("data-descriptor=\"/p/quantity/r/one/preview.json\""))
    assertTrue(html.contains("data-config=\"{&quot;keys&quot;"))
    assertEquals("public, max-age=0, must-revalidate", response.headers[HttpHeaders.CacheControl])
    assertEquals(null, response.headers[HttpHeaders.SetCookie])
  }

  @Test fun `HEAD works without an optional application plugin on every public representation`() = testApplication {
    fixture.bundle()
    mount()
    for (url in listOf(
      "/p/quantity/r/one/", "/p/quantity/r/one/embed/", "/p/quantity/r/one/preview.json",
      "/p/quantity/r/one/thumbnail.png", "/p/quantity/r/one/catalog/manifest.json",
      "/p/quantity/r/one/catalog/templates/${fixture.templateHash}.rc",
      "/assets/livery-preview/viewer.js", "/assets/livery-preview/viewer.css",
    )) {
      val get = client.fetch(url)
      val bytes = get.body<ByteArray>()
      assertEquals(HttpStatusCode.OK, get.status, url)
      assertTrue(bytes.isNotEmpty(), url)
      val expectedType = when {
        url.endsWith("/preview.json") -> "application/vnd.livery.preview+json"
        url.endsWith("/thumbnail.png") -> "image/png"
        url.endsWith("/catalog/manifest.json") -> "application/json"
        else -> null
      }
      if (expectedType != null) assertEquals(expectedType, get.headers[HttpHeaders.ContentType], url)
      val head = client.fetch(url, HttpMethod.Head)
      assertEquals(HttpStatusCode.OK, head.status, url)
      assertEquals(get.headers[HttpHeaders.ContentType], head.headers[HttpHeaders.ContentType], url)
      assertEquals(get.headers[HttpHeaders.ETag], head.headers[HttpHeaders.ETag], url)
      assertEquals(bytes.size.toString(), head.headers[HttpHeaders.ContentLength], url)
      assertEquals(0, head.body<ByteArray>().size, url)
    }
  }

  @Test fun `embed uses full canonical URL and only operator code assets are served`() = testApplication {
    fixture.bundle()
    mount()
    val embed = client.fetch("/p/quantity/r/one/embed/")
    assertEquals(HttpStatusCode.OK, embed.status)
    val html = embed.bodyAsText()
    assertTrue(html.contains("data-mode=\"embed\""))
    assertTrue(html.contains("data-canonical=\"https://preview.example/p/quantity/r/one/\""))
    assertTrue(client.fetch("/assets/livery-preview/viewer.js").headers[HttpHeaders.ContentType]!!.contains("javascript"))
    for (url in listOf("/assets/livery-preview/credentials.json", "/p/quantity/r/one/embed/preview.json", "/p/quantity/r/one/publication-files.json")) {
      assertEquals(HttpStatusCode.NotFound, client.fetch(url).status, url)
    }
  }

  @Test fun `pinned revision and validators survive newer publication but never override withdrawal`() = testApplication {
    val first = fixture.bundle()
    mount()
    val before = client.fetch("/p/quantity/r/one/preview.json")
    val original = before.bodyAsText()
    val etag = before.headers[HttpHeaders.ETag]!!
    fixture.bundle(revision = "two", title = "New title")
    assertEquals(original, client.fetch("/p/quantity/r/one/preview.json").bodyAsText())
    assertNotEquals(original, client.fetch("/p/quantity/r/two/preview.json").bodyAsText())
    for (method in listOf(HttpMethod.Get, HttpMethod.Head)) {
      val unchanged = client.fetch("/p/quantity/r/one/preview.json", method, etag)
      assertEquals(HttpStatusCode.NotModified, unchanged.status)
      assertEquals(0, unchanged.body<ByteArray>().size)
    }
    Files.writeString(first.resolve("withdrawn"), "operator withdrawal")
    for (suffix in listOf("", "embed/", "preview.json", "thumbnail.png", "catalog/manifest.json")) {
      for (method in listOf(HttpMethod.Get, HttpMethod.Head)) {
        val gone = client.fetch("/p/quantity/r/one/$suffix", method, etag)
        assertEquals(HttpStatusCode.Gone, gone.status)
        assertEquals("no-store", gone.headers[HttpHeaders.CacheControl])
        if (method == HttpMethod.Head) assertEquals(0, gone.body<ByteArray>().size)
      }
    }
    assertEquals(HttpStatusCode.OK, client.fetch("/p/quantity/r/two/").status)
  }

  @Test fun `missing or damaged publication returns uncached unavailable responses including HEAD`() = testApplication {
    val bundle = fixture.bundle()
    mount()
    Files.writeString(bundle.resolve("preview.json"), "changed without inventory")
    for (method in listOf(HttpMethod.Get, HttpMethod.Head)) {
      for (url in listOf("/p/quantity/r/one/", "/p/missing/r/one/", "/p/quantity/r/one/thumbnail.png")) {
        val response = client.fetch(url, method)
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        if (method == HttpMethod.Head) assertEquals(0, response.body<ByteArray>().size)
      }
    }
  }

  @Test fun `absence of a viewer keeps static image title and canonical fallback available`() = testApplication {
    fixture.bundle()
    mount(viewer = false)
    val response = client.fetch("/p/quantity/r/one/")
    assertEquals(HttpStatusCode.OK, response.status)
    val html = response.bodyAsText()
    assertTrue(html.contains("Component preview fallback"))
    assertTrue(html.contains("thumbnail.png"))
    assertFalse(html.contains("data-livery-preview"))
  }

  private suspend fun HttpClient.fetch(url: String, method: HttpMethod = HttpMethod.Get, etag: String? = null): HttpResponse =
    request(url) {
      this.method = method
      if (etag != null) headers.append(HttpHeaders.IfNoneMatch, etag)
    }
}
