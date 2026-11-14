package ee.schimke.livery.compose

import okio.ByteString
import okio.ByteString.Companion.toByteString
import androidx.compose.foundation.layout.Column
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import ee.schimke.livery.client.ActionRouter
import ee.schimke.livery.client.Bindings
import ee.schimke.livery.client.Clock
import ee.schimke.livery.client.FetchResult
import ee.schimke.livery.client.Fetcher
import ee.schimke.livery.client.ImageResolver
import ee.schimke.livery.client.JavaCrypto
import ee.schimke.livery.client.LoadedTemplate
import ee.schimke.livery.client.MemoryBlobStore
import ee.schimke.livery.client.MemoryKeyValueStore
import ee.schimke.livery.client.PlanResult
import ee.schimke.livery.client.RedirectingFetcher
import ee.schimke.livery.client.RenderPlan
import ee.schimke.livery.client.StaticSource
import ee.schimke.livery.client.TemplateRepository
import ee.schimke.livery.client.TrustPolicy
import ee.schimke.livery.client.slotTag
import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.livery.protocol.AssetEntry
import ee.schimke.livery.protocol.AssetKind
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.FontFace
import ee.schimke.livery.protocol.NamedValue
import ee.schimke.livery.protocol.PlayerCapabilities
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.Target
import ee.schimke.livery.protocol.TemplateEntry
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * A feed draws many cards of few templates, and none of them may show a user a blank or
 * half-drawn frame that only the first card of a template had a reason for. These play the sample
 * catalog's real banner document, given a font asset so that typefaces have to be loaded.
 */
@OptIn(ExperimentalTestApi::class)
class FirstFrameTest {
  private val samples = File(requireNotNull(System.getProperty("livery.samples")) { "run through Gradle: gradle/sample-catalogs.gradle.kts" })
  private val blobs = MemoryBlobStore()
  private val clock = Clock { 1_000L }
  private val repository = TemplateRepository(
    StaticSource("https://catalog.invalid/"),
    Fetcher { _, _ -> FetchResult.Failed(503, "offline") },
    JavaCrypto,
    TrustPolicy.AllowUnsigned,
    blobs,
    MemoryKeyValueStore(),
    clock,
    listOf(PlayerCapabilities(Surfaces.IN_APP, maxRcApiLevel = 7, profiles = setOf("androidx"))),
  )
  private val offline = object : RedirectingFetcher {
    override suspend fun get(url: String, etag: String?) = FetchResult.Failed(503, "offline")
    override suspend fun get(url: String, etag: String?, maxBytes: Long, allowRedirect: (String) -> Boolean) = FetchResult.Failed(503, "offline")
  }
  private val host = host()

  private fun host(
    firstFrameBudgetMillis: Long = DEFAULT_FIRST_FRAME_BUDGET_MILLIS,
    placeholder: @androidx.compose.runtime.Composable (LiveryPlaceholder) -> Unit = {},
  ) = LiveryHost(
    repository = repository,
    actions = ActionRouter(emptyMap(), { false }, { }, clock),
    events = { },
    clock = clock,
    images = ImageResolver(repository, offline, JavaCrypto),
    firstFrameBudgetMillis = firstFrameBudgetMillis,
    placeholder = placeholder,
  )
  private val contract = Contract("promo/banner", 1)

  init {
    runBlocking {
      blobs.put(FONT, File(samples, "fonts/Placeholder-Bold.ttf").readBytes().toByteString())
      blobs.put(PICTURE, png())
    }
  }

  private fun template(sha256: String = "banner") = LoadedTemplate(
    entry = TemplateEntry(sha256, 0, "banner-classic", contract.ref, Target(Surfaces.IN_APP, 7)),
    design = null,
    bytes = File(samples, "designs/banner-classic/in-app.rc").readBytes().toByteString(),
    origin = LoadedTemplate.Origin.Bundled,
    contract = contract,
    assets = listOf(AssetEntry(FONT, 0, "font/ttf", AssetKind.Font, font = FontFace("Placeholder Sans", 700), license = "OFL-1.1")),
  )

  private fun plan(title: String, template: LoadedTemplate = template()) =
    RenderPlan(template, contract, Bindings(mapOf("headline" to NamedValue.Str(title))))

  @Test fun aTemplatesFirstCardHasToLoadItsFonts() {
    assertNull(host.resources.typefacesNow(template()), "nothing is in memory yet, so this test means something")
  }

  @Test fun aSecondCardOfATemplateDrawsOnItsFirstFrame() = runComposeUiTest {
    var second by mutableStateOf(false)
    val tag = plan("one").slotTag
    setContent {
      Column {
        LiveryTemplate(plan("one"), host, Modifier.size(320.dp, 120.dp))
        if (second) LiveryTemplate(plan("two"), host, Modifier.size(320.dp, 120.dp))
      }
    }
    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }

    mainClock.autoAdvance = false
    second = true
    mainClock.advanceTimeByFrame()
    assertEquals(2, onAllNodesWithTag(tag).fetchSemanticsNodes().size, "the second card waited for fonts the first had loaded")
  }

  @Test fun aSlotPlannedWithinTheBudgetDrawsOnTheScreensFirstFrame() = runComposeUiTest {
    // Nothing prepared ahead: the slot plans, prepares and loads fonts while its first frame waits.
    mainClock.autoAdvance = false
    setContent {
      LiverySlot(1, host, Modifier.size(320.dp, 120.dp), fallback = { Box(Modifier.testTag(FALLBACK)) }) {
        delay(30)
        PlanResult.Ready(plan("one"))
      }
    }
    mainClock.advanceTimeByFrame()
    assertEquals(1, onAllNodesWithTag(plan("one").slotTag).fetchSemanticsNodes().size, "the first frame did not wait for the card")
    assertEquals(0, onAllNodesWithTag(FALLBACK).fetchSemanticsNodes().size)
  }

  @Test fun aSlotThatMissesTheBudgetDrawsItsFallbackFirst() = runComposeUiTest {
    val host = host(firstFrameBudgetMillis = 50)
    mainClock.autoAdvance = false
    setContent {
      LiverySlot(1, host, Modifier.size(320.dp, 120.dp), fallback = { Box(Modifier.testTag(FALLBACK)) }) {
        delay(2_000)
        PlanResult.Ready(plan("one"))
      }
    }
    mainClock.advanceTimeByFrame()
    assertEquals(1, onAllNodesWithTag(FALLBACK).fetchSemanticsNodes().size, "a slow plan held the first frame past its budget")
  }

  @Test fun afterTheFirstFrameASlotNeverWaits() = runComposeUiTest {
    var second by mutableStateOf(false)
    mainClock.autoAdvance = false
    setContent {
      Column {
        LiverySlot(1, host, Modifier.size(320.dp, 120.dp)) { PlanResult.Ready(plan("one")) }
        if (second) {
          LiverySlot(2, host, Modifier.size(320.dp, 120.dp), fallback = { Box(Modifier.testTag(FALLBACK)) }) {
            delay(30)
            PlanResult.Ready(plan("two"))
          }
        }
      }
    }
    repeat(3) { mainClock.advanceTimeByFrame() }
    assertTrue(host.resources.firstFrameDrawn)
    // A card arriving in a scroll: its frame does not wait, it shows its fallback until planned.
    second = true
    mainClock.advanceTimeByFrame()
    assertEquals(1, onAllNodesWithTag(FALLBACK).fetchSemanticsNodes().size, "a card after the first frame held its frame")
  }

  @Test fun aCardArrivingAfterTheFirstFrameShowsTheHostsPlaceholder() = runComposeUiTest {
    val host = host(placeholder = { Box(Modifier.size(320.dp, 40.dp).testTag("placeholder:${it.slotId}")) })
    var second by mutableStateOf(false)
    mainClock.autoAdvance = false
    setContent {
      Column {
        LiverySlot(1, host, Modifier.size(320.dp, 120.dp)) { PlanResult.Ready(plan("one")) }
        if (second) {
          LiverySlot(2, host, Modifier.size(320.dp, 120.dp), slotId = "feed") {
            delay(30)
            PlanResult.Ready(plan("two"))
          }
        }
      }
    }
    repeat(3) { mainClock.advanceTimeByFrame() }
    second = true
    mainClock.advanceTimeByFrame()
    assertEquals(1, onAllNodesWithTag("placeholder:feed").fetchSemanticsNodes().size, "no placeholder while the card planned")
  }

  @Test fun aSplashScreenStartupMeansNothingWaits() = runComposeUiTest {
    // The app holds its splash screen on a startup: the frame behind it need not wait as well.
    runBlocking { host.resources.startup(this) {}.await() }
    mainClock.autoAdvance = false
    setContent {
      LiverySlot(1, host, Modifier.size(320.dp, 120.dp), fallback = { Box(Modifier.testTag(FALLBACK)) }) {
        delay(30)
        PlanResult.Ready(plan("one"))
      }
    }
    mainClock.advanceTimeByFrame()
    assertEquals(1, onAllNodesWithTag(FALLBACK).fetchSemanticsNodes().size, "a frame behind a splash screen waited")
  }

  @Test fun aHostWithNoBudgetNeverWaits() = runComposeUiTest {
    val host = host(firstFrameBudgetMillis = 0)
    mainClock.autoAdvance = false
    setContent {
      LiverySlot(1, host, Modifier.size(320.dp, 120.dp), fallback = { Box(Modifier.testTag(FALLBACK)) }) {
        delay(30)
        PlanResult.Ready(plan("one"))
      }
    }
    mainClock.advanceTimeByFrame()
    assertEquals(1, onAllNodesWithTag(FALLBACK).fetchSemanticsNodes().size, "a host with no budget waited")
  }

  @Test fun aPreloadedCardDrawsOnItsFirstFrame() = runComposeUiTest {
    runBlocking { host.resources.preload(plan("one")) }
    // What makes the first frame complete: composition reads all of it without suspending. (The
    // test clock runs an effect that finishes without suspending before its first frame, so the
    // assertion below alone could not tell.)
    assertNotNull(host.resources.typefacesNow(template()), "a preload leaves the typefaces readable during composition")
    mainClock.autoAdvance = false
    setContent { LiveryTemplate(plan("one"), host, Modifier.size(320.dp, 120.dp)) }
    mainClock.advanceTimeByFrame()
    assertEquals(1, onAllNodesWithTag(plan("one").slotTag).fetchSemanticsNodes().size)
  }

  @Test fun anotherTemplateWithTheSameFaceNeedsNoLoad() = runBlocking {
    host.resources.typefaces(template("banner"))
    assertNotNull(host.resources.typefacesNow(template("another-banner")), "the face is in memory under its own hash")
    Unit
  }

  @Test fun aTemplateWhoseFontsChangedDoesNotDrawWithTheOldOnes() = runBlocking {
    host.resources.typefaces(template())
    val refreshed = template().copy(assets = listOf(AssetEntry(OTHER_FONT, 0, "font/ttf", AssetKind.Font, font = FontFace("Placeholder Sans", 700), license = "OFL-1.1")))
    assertNull(host.resources.typefacesNow(refreshed), "the same document with another face must load that face")
    Unit
  }

  @Test fun everyCardOfATemplateSharesOneDecodedDocument() {
    assertSame(host.resources.prepared(template()), host.resources.prepared(template()))
  }

  @Test fun anImageOneSlotLoadedIsInMemoryForTheNext() = runBlocking {
    val bitmap = RcBitmapData(1, 2, 2, 0, RcBitmapData.ENCODING_URL, "asset:$PICTURE".encodeToByteArray())
    val first = host.resources.imageLoader { Bindings.EMPTY }
    assertNull(first.cached(bitmap))
    assertNotNull(first.load(bitmap))
    assertNotNull(host.resources.imageLoader { Bindings.EMPTY }.cached(bitmap), "another slot draws it on its first frame")
    Unit
  }

  @Test fun anAppImageIsAskedForEveryTime() = runBlocking {
    var version = 0
    val resolver = ImageResolver(repository, offline, JavaCrypto, app = { _, _, _ -> version++; png() })
    val resources = LiveryResources(repository, resolver)
    val bitmap = RcBitmapData(1, 2, 2, 0, RcBitmapData.ENCODING_URL, "app:avatar".encodeToByteArray())
    assertNotNull(resources.imageLoader { Bindings.EMPTY }.load(bitmap))
    assertNull(resources.imageLoader { Bindings.EMPTY }.cached(bitmap), "the app may have changed the picture")
    assertNotNull(resources.imageLoader { Bindings.EMPTY }.load(bitmap))
    assertEquals(2, version)
  }

  @Test fun theSameFontBytesDeclaredAsAnotherFaceAreAnotherFont() = runBlocking {
    host.resources.typefaces(template())
    val italic = template().copy(assets = listOf(AssetEntry(FONT, 0, "font/ttf", AssetKind.Font, font = FontFace("Placeholder Sans", 700, italic = true), license = "OFL-1.1")))
    assertNull(host.resources.typefacesNow(italic), "a Font is built for its declared weight and style")
    Unit
  }

  @Test fun aTemplateWithMoreFacesThanTheCacheKeepsGetsThemAll() = runBlocking {
    runBlocking { blobs.put(OTHER_FONT, File(samples, "fonts/Placeholder-Bold.ttf").readBytes().toByteString()) }
    val small = LiveryResources(repository, maxFonts = 1)
    val twoFaces = template().copy(assets = listOf(
      AssetEntry(FONT, 0, "font/ttf", AssetKind.Font, font = FontFace("Placeholder Sans", 700), license = "OFL-1.1"),
      AssetEntry(OTHER_FONT, 0, "font/ttf", AssetKind.Font, font = FontFace("Placeholder Serif", 400), license = "OFL-1.1"),
    ))
    val loader = small.typefaces(twoFaces)
    assertNotNull(loader.typeface("Placeholder Sans", null), "the first face survived the second's load")
    assertNotNull(loader.typeface("Placeholder Serif", null))
    assertTrue("Placeholder Sans" in loader.families && "Placeholder Serif" in loader.families)
    Unit
  }

  @Test fun aSlotsLastAnswerIsOnlyReusedAtTheSameRevision() {
    val ready = PlanResult.Ready(plan("one"))
    host.resources.keepPlan("feed", "item-1", 3, ready)
    assertSame(ready, host.resources.lastPlan("feed", "item-1", 3))
    assertNull(host.resources.lastPlan("feed", "item-1", 4), "a refreshed catalog may answer differently")
    assertNull(host.resources.lastPlan("sidebar", "item-1", 3), "another slot with an equal key has its own answer")
  }

  @Test fun aPreparedSlotHasItsAnswerAndItsFontsBeforeItComposes() = runBlocking {
    host.resources.prepareSlot("feed", "item-1") { PlanResult.Ready(plan("one")) }
    assertNotNull(host.resources.lastPlan("feed", "item-1", repository.revision.value))
    assertNotNull(host.resources.typefacesNow(template()))
    Unit
  }

  private fun png(): ByteString = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", it) }.toByteArray().toByteString()

  private companion object {
    const val FALLBACK = "fallback"
    const val FONT = "f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0"
    const val OTHER_FONT = "b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2"
    const val PICTURE = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"
  }
}
