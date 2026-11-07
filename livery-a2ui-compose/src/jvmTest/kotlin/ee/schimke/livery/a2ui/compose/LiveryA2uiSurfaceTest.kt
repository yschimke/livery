package ee.schimke.livery.a2ui.compose

import ee.schimke.livery.publisher.build
import androidx.a2ui.compose.runtime.A2uiMessageParser
import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.a2ui.compose.ui.A2uiMessageProcessor
import androidx.a2ui.model.catalog.functions.A2uiLocaleProvider
import androidx.a2ui.model.processor.A2uiSurfaceModel
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
import androidx.compose.ui.unit.dp
import ee.schimke.livery.client.Clock
import ee.schimke.livery.client.FetchResult
import ee.schimke.livery.client.Fetcher
import ee.schimke.livery.client.JavaCrypto
import ee.schimke.livery.client.MemoryBlobStore
import ee.schimke.livery.client.MemoryKeyValueStore
import ee.schimke.livery.client.SlotPlanner
import ee.schimke.livery.client.StaticSource
import ee.schimke.livery.client.TemplateRepository
import ee.schimke.livery.client.TrustPolicy
import ee.schimke.livery.instantiate.DocumentAdmission
import ee.schimke.livery.protocol.PlayerCapabilities
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.TemplateEvent
import ee.schimke.livery.publisher.CatalogBuilder
import ee.schimke.livery.publisher.Layout
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * An A2UI surface on the desktop JVM whose catalog is the Material basic catalog plus a Livery
 * component per playable contract of the sample catalogs (`samples/catalog`, plus `samples/two-way`): JSON messages parsed and applied by A2UI's
 * engine (the Compose Multiplatform port), templates from the catalog `livery-publish` builds,
 * offline (only the bundled copy, as an app's first launch without a network), played by
 * rc-players' Compose player.
 */
@OptIn(ExperimentalTestApi::class)
abstract class LiveryA2uiSurfaceTest(private val documents: DocumentAdmission? = null) {
  private val samples = File(System.getProperty("livery.samples") ?: "../samples/catalog")

  /** The sample catalog with the two-way one's contracts and designs added (it needs level 7). */
  private val tree = Files.createTempDirectory("livery-a2ui").toFile().also { tree ->
    samples.copyRecursively(tree)
    for (dir in listOf("contracts", "designs")) File(samples.parentFile, "two-way/$dir").copyRecursively(File(tree, dir))
    prepare(tree)
  }
  private val built = CatalogBuilder(now = { 0 }).build(tree, Layout.Static, serial = 0)
  private val caps = PlayerCapabilities(Surfaces.IN_APP, maxRcApiLevel = 7, profiles = setOf("androidx"))
  private val clock = Clock { 1_000L }

  /**
   * Changes the catalog source tree before it is built. Runs during construction: it may use only
   * [tree], not the subclass's own properties.
   */
  protected open fun prepare(tree: File) {}

  /** Livery's events: impressions, actions, errors. */
  protected val events = mutableListOf<TemplateEvent>()

  /** What the client sent the agent: A2UI events and errors. */
  protected val outbound = mutableListOf<A2uiClientToServerMessage>()

  /** What the app's own handlers received, by action kind, for actions the agent didn't wire. */
  protected val appHandled = mutableListOf<Pair<String, Map<String, String>>>()

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

  private val host = LiveryA2uiHost(
    repository = repository,
    planner = SlotPlanner(repository, { events += it }, clock),
    capabilities = caps,
    events = { events += it },
    clock = clock,
    appActions = listOf("dismiss", "navigate", "app:vote").associateWith { kind -> { _, _, args -> appHandled += kind to args } },
    documents = documents,
  )

  private val basic: A2uiCatalog = materialA2uiBasicCatalogV1(
    image = MaterialA2uiBasicCatalogV1Defaults.image { _, _, _, _, _ -> },
    video = MaterialA2uiBasicCatalogV1Defaults.video { _, _, _ -> },
    audioPlayer = MaterialA2uiBasicCatalogV1Defaults.audioPlayer { _, _, _, _ -> },
    urlOpener = {},
    messageFormatter = { pattern, _, _ -> pattern },
    localeProvider = A2uiLocaleProvider.Default,
  )

  protected val catalog: A2uiCatalog = LiveryA2uiCompose.catalog(CATALOG_ID, basic, host, runBlocking { LiveryA2uiCompose.playableContracts(repository, caps) })

  private val processor = A2uiMessageProcessor(catalogs = listOf(catalog))
  private val parser = A2uiMessageParser()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

  init {
    // Unconfined and undispatched: a message is applied by the time processMessage returns.
    scope.launch(start = CoroutineStart.UNDISPATCHED) { processor.collectMessages() }
    scope.launch(start = CoroutineStart.UNDISPATCHED) { processor.outboundEvents.collect { outbound += it } }
  }

  @AfterTest fun close() = scope.cancel()

  protected val create = """{"version":"v0.9","createSurface":{"surfaceId":"$SURFACE","catalogId":"$CATALOG_ID"}}"""

  /** Applies agent messages, in order. */
  protected fun send(vararg messages: String) = messages.forEach { processor.processMessage(parser.parse(it)) }

  protected fun surface(): A2uiSurfaceModel = assertNotNull(processor.activeSurfaces.value.firstOrNull { it.id == SURFACE })

  /** Draws the surface in a phone-width column and waits for the first Livery template's first frame. */
  protected fun ComposeUiTest.show() {
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

  /** The surface as drawn, kept under `-Plivery.render.out` as `<name>.png`. */
  protected fun ComposeUiTest.capture(name: String): BufferedImage {
    val image = onNodeWithTag("surface").captureToImage().toAwtImage()
    System.getProperty("livery.render.out")?.let { ImageIO.write(image, "png", File(it).apply { mkdirs() }.resolve("$name.png")) }
    return image
  }

  protected fun sameImage(a: BufferedImage, b: BufferedImage): Boolean =
    a.width == b.width && a.height == b.height && (0 until a.width).all { x -> (0 until a.height).all { y -> a.getRGB(x, y) == b.getRGB(x, y) } }

  companion object {
    const val CATALOG_ID = "https://livery.example/a2ui/catalog/sample-v1"
    const val SURFACE = "chat-1"
  }
}
