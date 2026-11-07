package ee.schimke.livery.a2ui.compose

import androidx.a2ui.compose.runtime.A2uiMessageParser
import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.a2ui.compose.ui.A2uiMessageProcessor
import androidx.a2ui.compose.ui.toJsonSchemaString
import androidx.a2ui.model.catalog.functions.A2uiLocaleProvider
import androidx.a2ui.model.processor.A2uiSurfaceModel
import androidx.a2ui.model.protocol.A2uiClientErrorMessage
import androidx.a2ui.model.protocol.A2uiClientEventMessage
import androidx.a2ui.model.protocol.A2uiClientToServerMessage
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.material3.a2ui.catalog.MaterialA2uiBasicCatalogV1Defaults
import androidx.compose.material3.a2ui.catalog.materialA2uiBasicCatalogV1
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import ee.schimke.livery.client.FetchResult
import ee.schimke.livery.client.Fetcher
import ee.schimke.livery.client.JavaCrypto
import ee.schimke.livery.client.MemoryBlobStore
import ee.schimke.livery.client.MemoryKeyValueStore
import ee.schimke.livery.client.SlotPlanner
import ee.schimke.livery.client.StaticSource
import ee.schimke.livery.client.TemplateRepository
import ee.schimke.livery.client.TrustPolicy
import ee.schimke.livery.protocol.PlayerCapabilities
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.TemplateEvent
import ee.schimke.livery.publisher.CatalogBuilder
import ee.schimke.livery.publisher.Layout
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The sample catalog's `promo/banner@1` inside an A2UI surface, end to end on the desktop JVM: JSON
 * messages parsed and applied by A2UI's engine (the Compose Multiplatform port), a basic-catalog
 * `Text` beside a `PromoBanner` generated from the contract, the template chosen from the sample
 * catalog as built by `livery-publish` and played by rc-players' Compose player, its values bound
 * from the surface's data model.
 */
@OptIn(ExperimentalTestApi::class)
class PromoBannerA2uiTest {
  private val samples = File(System.getProperty("livery.samples") ?: "../samples/catalog")
  private val built = CatalogBuilder(now = { 0 }).build(samples, Layout.Static, serial = 0)
  private val caps = PlayerCapabilities(Surfaces.IN_APP, maxRcApiLevel = 7, profiles = setOf("androidx"))
  private val events = mutableListOf<TemplateEvent>()
  private val clock = ee.schimke.livery.client.Clock { 1_000L }

  /** Offline: the catalog is only the bundled copy, as an app's first launch without a network. */
  private val repository = TemplateRepository(
    StaticSource("https://catalog.invalid/"),
    Fetcher { _, _ -> FetchResult.Failed(503, "offline") },
    JavaCrypto,
    TrustPolicy.AllowUnsigned,
    MemoryBlobStore(),
    MemoryKeyValueStore(),
    clock,
    listOf(caps),
    TemplateRepository.Bundled(built.manifest) { sha -> built.files.entries.firstOrNull { it.key.endsWith("$sha.rc") || it.key.contains(sha) }?.value },
  )

  private val navigations = mutableListOf<Map<String, String>>()
  private val host = LiveryA2uiHost(
    repository = repository,
    planner = SlotPlanner(repository, { events += it }, clock),
    capabilities = caps,
    events = { events += it },
    clock = clock,
    appActions = mapOf("dismiss" to { _, _, args -> navigations += args }, "navigate" to { _, _, args -> navigations += args }),
  )

  private val basic: A2uiCatalog = materialA2uiBasicCatalogV1(
    image = MaterialA2uiBasicCatalogV1Defaults.image { _, _, _, _, _ -> },
    video = MaterialA2uiBasicCatalogV1Defaults.video { _, _, _ -> },
    audioPlayer = MaterialA2uiBasicCatalogV1Defaults.audioPlayer { _, _, _, _ -> },
    urlOpener = {},
    messageFormatter = { pattern, _, _ -> pattern },
    localeProvider = A2uiLocaleProvider.Default,
  )

  private val contracts = runBlocking { LiveryA2ui.playableContracts(repository, caps) }
  private val catalog = LiveryA2ui.catalog(CATALOG_ID, basic, host, contracts)

  private val processor = A2uiMessageProcessor(catalogs = listOf(catalog))
  private val parser = A2uiMessageParser()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
  private val outbound = mutableListOf<A2uiClientToServerMessage>()

  init {
    // Unconfined and undispatched: a message is applied by the time processMessage returns.
    scope.launch(start = CoroutineStart.UNDISPATCHED) { processor.collectMessages() }
    scope.launch(start = CoroutineStart.UNDISPATCHED) { processor.outboundEvents.collect { outbound += it } }
  }

  @AfterTest fun close() = scope.cancel()

  private fun send(vararg messages: String) = messages.forEach { processor.processMessage(parser.parse(it)) }

  private fun surface(): A2uiSurfaceModel = assertNotNull(processor.activeSurfaces.value.firstOrNull { it.id == SURFACE })

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

  private val create = """{"version":"v0.9","createSurface":{"surfaceId":"$SURFACE","catalogId":"$CATALOG_ID"}}"""

  private fun ComposeUiTest.show() {
    val surface = surface()
    setContent {
      MaterialTheme {
        Surface {
          Box(Modifier.testTag("surface").padding(16.dp).width(360.dp)) {
            A2uiSurface(surfaceModel = surface, transitionSpec = null)
          }
        }
      }
    }
    waitUntil(timeoutMillis = 10_000) { events.any { it.type == TemplateEvent.Type.Impression } }
    waitForIdle()
  }

  private fun ComposeUiTest.capture(name: String): BufferedImage {
    val image = onNodeWithTag("surface").captureToImage().toAwtImage()
    System.getProperty("livery.render.out")?.let { ImageIO.write(image, "png", File(it).apply { mkdirs() }.resolve("$name.png")) }
    return image
  }

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
    val impression = events.first { it.type == TemplateEvent.Type.Impression }
    assertEquals("banner-classic", impression.designId)
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

  @Test fun anActionTheAgentWiredBecomesAnA2uiEventWithItsArguments() = runComposeUiTest {
    send(create, data("Try Premium free"), components())
    show()
    // The template's cta, as the player reports a host action: route through the same router.
    val promo = catalog.components["PromoBanner"] as LiveryA2uiComponent
    assertEquals("promo/banner@1", promo.contract.ref)
    tapCta()
    waitUntil(timeoutMillis = 5_000) { outbound.any { it is A2uiClientEventMessage } }
    val event = outbound.filterIsInstance<A2uiClientEventMessage>().first()
    assertEquals("startTrial", event.type)
    assertEquals("promo", event.componentId)
    assertEquals("premium-30", event.context["offer"])
    assertTrue(events.any { it.type == TemplateEvent.Type.Action && it.action == "cta" }, "Livery's own Action event too")
  }

  /** The banner's CTA, found by its text in the player's semantics, or by position. */
  private fun ComposeUiTest.tapCta() {
    onNodeWithText("Start trial", useUnmergedTree = true).performClick()
  }

  private fun sameImage(a: BufferedImage, b: BufferedImage): Boolean =
    a.width == b.width && a.height == b.height && (0 until a.width).all { x -> (0 until a.height).all { y -> a.getRGB(x, y) == b.getRGB(x, y) } }

  companion object {
    const val CATALOG_ID = "https://livery.example/a2ui/catalog/sample-v1"
    const val SURFACE = "chat-1"
  }
}

