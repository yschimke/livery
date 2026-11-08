package ee.schimke.livery.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class KtorDeciderTest {
  private val request = DecisionRequest(TemplateQuery("promo/banner@1", Fx.inApp, widthDp = 360, theme = "dark", minor = 2),
    buildJsonObject { put("url", "https://agent.invalid/"); put("offer", "sale") }, DecisionContext(1, "test", "en-GB", 4, "app-id"))

  @Test fun postsOnlyToTheAppEndpointWithDeviceQueryAndAgentParams() = runTest {
    val http = HttpClient(MockEngine { req ->
      assertEquals("https://app.example/v1/decisions/query", req.url.toString())
      assertEquals(HttpMethod.Post, req.method)
      val received = Json.decodeFromString(DecisionRequest.serializer(), (req.body as TextContent).text)
      assertEquals(request, received)
      respond("""{"placement":"chat","decisionId":"server:1","template":{"designId":"banner"},"data":{},"future":true}""")
    })
    assertEquals("server:1", KtorDecider("https://app.example/v1/decisions/query", http).decide(request)?.decisionId)
  }

  @Test fun redirectsFailuresMalformedAndOversizedResponsesYieldNoDecision() = runTest {
    for ((status, body, headers) in listOf(
      Triple(HttpStatusCode.Found, "", headersOf(HttpHeaders.Location, "https://agent.invalid/")),
      Triple(HttpStatusCode.NoContent, "", Headers.Empty),
      Triple(HttpStatusCode.OK, "not json", Headers.Empty),
      Triple(HttpStatusCode.OK, " ".repeat(100), Headers.Empty),
      Triple(HttpStatusCode.OK, "{}", headersOf(HttpHeaders.ContentLength, "1000000")),
    )) {
      var calls = 0
      val http = HttpClient(MockEngine { calls++; respond(body, status, headers) }) { followRedirects = true }
      assertNull(KtorDecider("https://app.example/decide", http, maxBytes = 32).decide(request))
      assertEquals(1, calls, "no redirect is contacted")
    }
  }

  @Test fun existingDecidersNeedNoQueryImplementation() = runTest {
    assertNull(Decider { _, _ -> error("placement API must not receive a contract query") }.decide(request))
  }
}
