package ee.schimke.livery.a2ui.compose

import androidx.a2ui.compose.ui.toJsonSchemaString
import androidx.a2ui.model.protocol.A2uiClientErrorMessage
import androidx.a2ui.model.protocol.A2uiClientEventMessage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import ee.schimke.livery.protocol.TemplateEvent
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sample catalog's `promo/banner@1` inside an A2UI surface: a basic-catalog `Text` beside a
 * `PromoBanner` generated from the contract, its values bound from the surface's data model.
 */
@OptIn(ExperimentalTestApi::class)
class PromoBannerA2uiTest : LiveryA2uiSurfaceTest() {
  private fun data(title: String) =
    """{"version":"v0.9","updateDataModel":{"surfaceId":"$SURFACE","path":"/offer","value":{"title":"$title","body":"Offline downloads and no ads for 30 days."}}}"""

  private fun components(design: String = "banner-classic", headline: String = """{"path":"/offer/title"}""") =
    """
    {"version":"v0.9","updateComponents":{"surfaceId":"$SURFACE","components":[
      {"id":"root","component":"Column","children":["intro","promo"]},
      {"id":"intro","component":"Text","text":"Here is something for you","variant":"h5"},
      {"id":"promo","component":"PromoBanner","design":"$design",
        "headline":$headline,"body":{"path":"/offer/body"},"cta":"Start trial",
        "actions":{"cta":{"event":{"name":"startTrial","context":{"offer":"premium-30"}}}}}
    ]}}
    """.trimIndent()

  @Test fun theAgentIsOfferedTheContractAsAComponent() {
    val schema = catalog.toJsonSchemaString()
    assertTrue("\"PromoBanner\"" in schema, "the banner is in the catalog the client advertises")
    for (input in listOf("headline", "body", "cta", "hero", "accent", "design", "variant", "actions")) assertTrue("\"$input\"" in schema, "$input is a property")
    assertFalse("theme.primary" in schema, "theme roles are the host's, not the agent's")
    System.getProperty("livery.render.out")?.let { File(it).apply { mkdirs() }.resolve("catalog.schema.json").writeText(schema) }
  }

  @Test fun theBannerDrawsInsideTheSurfaceWithTheAgentsData() = runComposeUiTest {
    send(create, data("Try Premium free"), components())
    show()
    onNodeWithText("Here is something for you").assertExists()
    assertEquals("banner-classic", events.first { it.type == TemplateEvent.Type.Impression }.designId)
    val image = capture("promo-banner-classic")
    assertTrue(image.height > 120, "the banner has height: ${image.width}x${image.height}")
  }

  @Test fun aDataModelUpdateIsPushedIntoThePlayingDocument() = runComposeUiTest {
    send(create, data("Try Premium free"), components())
    show()
    val before = capture("promo-before")
    send(data("Premium, two months free"))
    waitForIdle()
    val after = capture("promo-after")
    assertFalse(sameImage(before, after), "the new headline draws")
    assertEquals(1, events.count { it.type == TemplateEvent.Type.Impression }, "the document was updated, not restarted")
  }

  @Test fun aDesignTheDeviceDoesntHaveFallsBackToTheAutomaticOne() = runComposeUiTest {
    send(create, data("Try Premium free"), components(design = "banner-from-next-season"))
    show()
    assertEquals("banner-classic", events.first { it.type == TemplateEvent.Type.Impression }.designId, "never nothing (PROTOCOL.md §5.2)")
  }

  @Test fun dataTheContractRefusesIsReportedToTheAgent() = runComposeUiTest {
    // headline is at most 40 characters.
    send(create, components(headline = "\"" + "x".repeat(41) + "\""))
    setContent { MaterialTheme { A2uiSurface(surfaceModel = surface(), transitionSpec = null) } }
    waitUntil(timeoutMillis = 10_000) { outbound.any { it is A2uiClientErrorMessage } }
    val error = outbound.filterIsInstance<A2uiClientErrorMessage>().first()
    assertTrue("PromoBanner" in error.message, error.message)
    assertTrue(events.none { it.type == TemplateEvent.Type.Impression }, "nothing else draws in its place")
  }

  @Test fun aNumberOutOfRangeIsRefusedNotNarrowed() = runComposeUiTest {
    // feed/article's `published` is a long; 1e20 doesn't fit one. Narrowed, it would bind as
    // Long.MAX_VALUE; passed through, ContractBinder refuses it.
    send(create, """{"version":"v0.9","updateComponents":{"surfaceId":"$SURFACE","components":[
      {"id":"root","component":"FeedArticle","title":"Remote Compose in production","published":1e20}]}}""")
    setContent { MaterialTheme { A2uiSurface(surfaceModel = surface(), transitionSpec = null) } }
    waitUntil(timeoutMillis = 10_000) { outbound.any { it is A2uiClientErrorMessage } }
    assertTrue(events.any { it.type == TemplateEvent.Type.Error && "published" in (it.detail ?: "") }, events.toString())
    assertTrue(events.none { it.type == TemplateEvent.Type.Impression })
  }

  @Test fun anActionTheAgentWiredBecomesAnA2uiEventWithItsArguments() = runComposeUiTest {
    send(create, data("Try Premium free"), components())
    show()
    onNodeWithText("Start trial", useUnmergedTree = true).performClick()
    waitUntil(timeoutMillis = 5_000) { outbound.any { it is A2uiClientEventMessage } }
    val event = outbound.filterIsInstance<A2uiClientEventMessage>().first()
    assertEquals("startTrial", event.type)
    assertEquals("promo", event.componentId)
    assertEquals("premium-30", event.context["offer"], "the agent's context")
    assertEquals("/premium", event.context["route"], "and the template's own argument")
    assertTrue(events.any { it.type == TemplateEvent.Type.Action && it.action == "cta" }, "Livery's own Action event too")
  }
}
