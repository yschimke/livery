package ee.schimke.livery.uibuilder

import okio.ByteString.Companion.toByteString
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The export API in common code, over Ktor: what a browser or IDE host calls. */
class UiBuilderClientTest {
  private val seen = mutableListOf<String>()
  private val http = HttpClient(MockEngine { request ->
    seen += request.url.toString()
    if (request.url.encodedPath.endsWith("/kitchen-tile/export.rc") && request.url.parameters["token"] == "s e/cret") {
      respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK, headersOf("X-UI-Builder-Revision", "42"))
    } else {
      respond("no such design", HttpStatusCode.NotFound)
    }
  }) { followRedirects = false }
  private val client = UiBuilderClient("https://ui.example/", "s e/cret", http)

  @Test fun exportsWithTheRevisionItDrew() = runTest {
    val e = client.export("kitchen-tile", revision = 7)
    assertEquals(byteArrayOf(1, 2, 3).toByteString(), e.bytes)
    assertEquals(42L, e.revision)
    assertEquals("https://ui.example/api/ui-builder/v1/designs/kitchen-tile", e.url)
    // The token is escaped in the query, and the revision asked for is sent.
    assertTrue(seen.single().contains("token=s+e%2Fcret") && seen.single().endsWith("revision=7"), seen.single())
  }

  @Test fun refusesAFailedExportOrABadId() = runTest {
    assertTrue("404" in assertFailsWith<IllegalStateException> { client.export("nope") }.message!!)
    assertFailsWith<IllegalArgumentException> { client.export("../x") }
    assertEquals(1, seen.size, "a bad id never reaches the network")
  }
}
