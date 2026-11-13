package ee.schimke.livery.compose

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rcplayer.compose.RcPreparation
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
    assertEquals(0, documents.misses, "a slot composed a template before it was prepared")
  }

  @Test fun aWarmedTemplateDrawsWithoutAPlayerEverWaiting() = runComposeUiTest {
    runBlocking { host.resources.readyToDraw(template) }
    mainClock.autoAdvance = false
    setContent { LiveryTemplate(plan(1), host, Modifier.size(320.dp, 120.dp)) }
    mainClock.advanceTimeByFrame()
    assertEquals(1, onAllNodesWithTag(plan(1).slotTag).fetchSemanticsNodes().size)
    assertEquals(0, host.resources.documents.misses)
  }
}
