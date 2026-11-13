package ee.schimke.livery.compose

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rcplayer.compose.RcDisplay
import ee.schimke.composeai.rcplayer.compose.RcPreparation
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.livery.client.ActionRouter
import ee.schimke.livery.client.Bindings
import ee.schimke.livery.client.Clock
import ee.schimke.livery.client.FetchResult
import ee.schimke.livery.client.Fetcher
import ee.schimke.livery.client.JavaCrypto
import ee.schimke.livery.client.LoadedTemplate
import ee.schimke.livery.client.MemoryBlobStore
import ee.schimke.livery.client.MemoryKeyValueStore
import ee.schimke.livery.client.PlanResult
import ee.schimke.livery.client.RenderPlan
import ee.schimke.livery.client.StaticSource
import ee.schimke.livery.client.TemplateRepository
import ee.schimke.livery.client.TrustPolicy
import ee.schimke.livery.client.slotTag
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.NamedValue
import ee.schimke.livery.protocol.PlayerCapabilities
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.Target
import ee.schimke.livery.protocol.TemplateEntry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * A host prepares its templates off the main thread by default ([LiveryHost.preparation]): a list
 * of slots that scrolls prepares each template once, in the background, and no slot ever composes a
 * template that is not prepared yet.
 */
@OptIn(ExperimentalTestApi::class)
class BackgroundPreparationTest {
  private val samples = File(requireNotNull(System.getProperty("livery.samples")) { "run through Gradle: gradle/sample-catalogs.gradle.kts" })
  private val clock = Clock { 1_000L }
  private val repository = TemplateRepository(
    StaticSource("https://catalog.invalid/"),
    Fetcher { _, _ -> FetchResult.Failed(503, "offline") },
    JavaCrypto,
    TrustPolicy.AllowUnsigned,
    MemoryBlobStore(),
    MemoryKeyValueStore(),
    clock,
    listOf(PlayerCapabilities(Surfaces.IN_APP, maxRcApiLevel = 7, profiles = setOf("androidx"))),
  )
  private val host = LiveryHost(repository, ActionRouter(emptyMap(), { false }, { }, clock), { }, clock)
  private val contract = Contract("promo/banner", 1)
  private val template = LoadedTemplate(
    entry = TemplateEntry("banner", 0, "banner-classic", contract.ref, Target(Surfaces.IN_APP, 7)),
    design = null,
    bytes = File(samples, "designs/banner-classic/in-app.rc").readBytes(),
    origin = LoadedTemplate.Origin.Bundled,
    contract = contract,
  )

  private fun plan(item: Int) = RenderPlan(template, contract, Bindings(mapOf("headline" to NamedValue.Str("Item $item"))))

  @Test fun theDefaultIsToPrepareInTheBackground() {
    assertIs<RcPreparation.Background>(host.preparation)
  }

  @Test fun scrollingAListOfSlotsNeverComposesAnUnpreparedTemplate() = runComposeUiTest {
    val list = LazyListState()
    setContent {
      LazyColumn(Modifier.size(320.dp, 360.dp), state = list) {
        items(60) { item ->
          LiverySlot(key = item, host = host, modifier = Modifier.size(320.dp, 120.dp), slotId = "feed") {
            PlanResult.Ready(plan(item))
          }
        }
      }
    }
    val tag = plan(0).slotTag
    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    for (item in 1 until 60 step 2) {
      runOnIdle { runBlocking { list.scrollToItem(item) } }
      waitForIdle()
    }
    val documents = host.resources.documents
    assertTrue(list.firstVisibleItemIndex > 50, "the list scrolled")
    assertEquals(1, documents.prepared, "one template, prepared once for every card")
    assertEquals(0, host.resources.misses, "a slot composed a template before it was prepared")
  }

  @Test fun aTemplateIsRewrittenForTheDisplayItIsDrawnOn() = runBlocking {
    val display = RcDisplay(density = 2f)
    val published = RcDocumentCodec.decode(template.bytes)
    val prepared = host.resources.prepare(template, display)
    assertTrue(prepared.document.operations.size < published.operations.size, "forDisplay left the template as published")
    assertSame(prepared, host.resources.preparedNow(template, display))
    assertNull(host.resources.preparedNow(template, RcDisplay(density = 3f)), "another display is another rewrite")
    Unit
  }

  /**
   * A splash screen's `startup` runs before anything has composed. Where the platform cannot say
   * what display that will be (here, the desktop JVM), the template is prepared as published; the
   * first card still draws on its first frame, from that.
   */
  @Test fun aTemplatePreparedBeforeTheDisplayWasKnownStillDrawsOnTheFirstFrame() = runComposeUiTest {
    runBlocking {
      host.resources.readyToDraw(template, display = null)
    }
    mainClock.autoAdvance = false
    setContent { LiveryTemplate(plan(1), host, Modifier.size(320.dp, 120.dp)) }
    mainClock.advanceTimeByFrame()
    assertEquals(1, onAllNodesWithTag(plan(1).slotTag).fetchSemanticsNodes().size)
  }

  /**
   * A card that cannot draw yet (its template's first, not prepared) holds the height the template
   * last drew at for its width, so the list does not move when it arrives.
   */
  @Test fun aCardThatCannotDrawYetHoldsItsTemplatesHeight() = runComposeUiTest {
    val widthPx = with(density) { 320.dp.roundToPx() }
    host.resources.keepHeight(template, widthPx, 240)
    mainClock.autoAdvance = false
    setContent {
      androidx.compose.foundation.layout.Box(Modifier.testTag("holder")) {
        LiveryTemplate(plan(1), host, Modifier.width(320.dp))
      }
    }
    mainClock.advanceTimeByFrame()
    val height = onNodeWithTag("holder").fetchSemanticsNode().size.height
    assertEquals(240, height, "the waiting card did not hold its template's height")
  }

  /**
   * A catalog can hold bytes this player cannot play (a placeholder, a newer wire format). Warming
   * skips it and its card draws nothing, rather than failing the app.
   */
  @Test fun aTemplateThePlayerCannotPlayDrawsNoCardAndDoesNotFailTheApp() = runComposeUiTest {
    val placeholder = template.copy(
      entry = template.entry.copy(sha256 = "placeholder"),
      bytes = "PLACEHOLDER banner-bold".encodeToByteArray(),
    )
    runBlocking { host.resources.readyToDraw(placeholder, RcDisplay(density.density, density.fontScale)) }
    assertTrue(host.resources.cannotPlay(placeholder))
    setContent {
      androidx.compose.foundation.layout.Box(Modifier.testTag("holder")) {
        LiveryTemplate(RenderPlan(placeholder, contract, Bindings(emptyMap())), host, Modifier.width(320.dp))
      }
    }
    waitForIdle()
    assertEquals(0, onNodeWithTag("holder").fetchSemanticsNode().size.height)
  }

  /** Not warmed: the card finds out itself, in the background, and still draws nothing. */
  @Test fun aCardOfATemplateThePlayerCannotPlayDoesNotFailTheApp() = runComposeUiTest {
    val placeholder = template.copy(
      entry = template.entry.copy(sha256 = "placeholder-2"),
      bytes = "PLACEHOLDER article-card".encodeToByteArray(),
    )
    setContent { LiveryTemplate(RenderPlan(placeholder, contract, Bindings(emptyMap())), host, Modifier.width(320.dp)) }
    waitUntil(timeoutMillis = 10_000) { host.resources.cannotPlay(placeholder) }
    waitForIdle()
  }

  @Test fun aWarmedTemplateDrawsWithoutAPlayerEverWaiting() = runComposeUiTest {
    // The test's own density: the display the template is drawn on.
    runBlocking { host.resources.readyToDraw(template, RcDisplay(density.density, density.fontScale)) }
    mainClock.autoAdvance = false
    setContent { LiveryTemplate(plan(1), host, Modifier.size(320.dp, 120.dp)) }
    mainClock.advanceTimeByFrame()
    assertEquals(1, onAllNodesWithTag(plan(1).slotTag).fetchSemanticsNodes().size)
    assertEquals(0, host.resources.misses)
  }
}
