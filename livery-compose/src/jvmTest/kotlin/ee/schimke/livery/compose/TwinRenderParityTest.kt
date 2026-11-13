package ee.schimke.livery.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rcplayer.compose.RcDisplay
import ee.schimke.livery.client.ActionRouter
import ee.schimke.livery.client.BindResult
import ee.schimke.livery.client.Clock
import ee.schimke.livery.client.ContractBinder
import ee.schimke.livery.client.FetchResult
import ee.schimke.livery.client.Fetcher
import ee.schimke.livery.client.JavaCrypto
import ee.schimke.livery.client.LoadedTemplate
import ee.schimke.livery.client.MemoryBlobStore
import ee.schimke.livery.client.MemoryKeyValueStore
import ee.schimke.livery.client.RenderPlan
import ee.schimke.livery.client.StaticSource
import ee.schimke.livery.client.TemplateRepository
import ee.schimke.livery.client.TrustPolicy
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.PlayerCapabilities
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.Target
import ee.schimke.livery.protocol.TemplateEntry
import ee.schimke.livery.twins.BannerData
import ee.schimke.livery.twins.BannerTwin
import ee.schimke.livery.twins.PollData
import ee.schimke.livery.twins.PollOption
import ee.schimke.livery.twins.PollTwin
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * The benchmark compares a feed of Livery templates with a feed of their Compose UI twins
 * (livery-baselineprofile/twins). That is only a comparison if both draw the same cards: each
 * sample template and its twin are rendered with the same data, size and density, and must match
 * pixel for pixel but for antialiasing. Both renders are kept in build/twin-parity to look at.
 */
class TwinRenderParityTest {
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
  private val out = File("build/twin-parity").also { it.mkdirs() }

  @Test fun theBannerTwinDrawsTheBannerTemplate() {
    val data = BannerData("Try Premium free", "Offline downloads and no ads for 30 days.", "Start trial")
    val json = JsonObject(mapOf("headline" to JsonPrimitive(data.headline), "body" to JsonPrimitive(data.body), "cta" to JsonPrimitive(data.cta)))
    compare("banner-classic", "promo-banner", json) { BannerTwin(data) }
  }

  @Test fun thePollTwinDrawsThePollTemplate() {
    val data = PollData(
      "Which language do you write your UI in?",
      "129 votes · you voted",
      listOf(PollOption("Kotlin", 0.53f, "53%", "✓"), PollOption("Swift", 0.26f, "26%"), PollOption("TypeScript", 0.21f, "21%")),
    )
    val json = JsonObject(
      mapOf(
        "question" to JsonPrimitive(data.question),
        "summary" to JsonPrimitive(data.summary),
        "options" to JsonArray(
          data.options.map {
            JsonObject(
              buildMap {
                put("label", JsonPrimitive(it.label))
                put("share", JsonPrimitive(it.share))
                put("result", JsonPrimitive(it.result))
                if (it.mark.isNotEmpty()) put("mark", JsonPrimitive(it.mark))
              },
            )
          },
        ),
      ),
    )
    compare("poll-card", "poll", json) { PollTwin(data) }
  }

  private fun compare(design: String, contractFile: String, data: JsonObject, twin: @Composable () -> Unit) {
    val contract = TemplateRepository.DefaultJson.decodeFromString(Contract.serializer(), File(samples, "contracts/$contractFile.json").readText())
    val template = LoadedTemplate(
      entry = TemplateEntry(design, 0, design, contract.ref, Target(Surfaces.IN_APP, 7)),
      design = null,
      bytes = File(samples, "designs/$design/in-app.rc").readBytes(),
      origin = LoadedTemplate.Origin.Bundled,
      contract = contract,
    )
    val bindings = (ContractBinder.bind(contract, data) as? BindResult.Ok)?.bindings ?: error("$design: ${ContractBinder.bind(contract, data)}")
    runBlocking { host.resources.readyToDraw(template, RcDisplay(DENSITY)) }
    val livery = render { LiveryTemplate(RenderPlan(template, contract, bindings), host) }
    val compose = render(twin)
    File(out, "$design-livery.png").writeBytes(livery.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    File(out, "$design-compose.png").writeBytes(compose.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    val differ = differingPixels(livery, compose)
    val share = differ.toDouble() / (WIDTH * HEIGHT)
    println("$design: ${"%.2f".format(share * 100)}% of pixels differ ($differ)")
    assertTrue(share < MAX_DIFFERING, "$design: ${"%.2f".format(share * 100)}% of pixels differ from its twin; see ${out.absolutePath}")
  }

  private fun render(content: @Composable () -> Unit): Image {
    val scene = ImageComposeScene(WIDTH, HEIGHT, Density(DENSITY)) { Box(Modifier.width((WIDTH / DENSITY).dp)) { content() } }
    return try {
      scene.render(0L)
      scene.render(FRAME_NANOS)
    } finally {
      scene.close()
    }
  }

  /** Pixels that differ by more than antialiasing does, in any channel. */
  private fun differingPixels(a: Image, b: Image): Int {
    val pa = Bitmap().apply { allocN32Pixels(WIDTH, HEIGHT) }.also { a.readPixels(it) }
    val pb = Bitmap().apply { allocN32Pixels(WIDTH, HEIGHT) }.also { b.readPixels(it) }
    var count = 0
    for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
      val ca = pa.getColor(x, y)
      val cb = pb.getColor(x, y)
      if ((0 until 32 step 8).any { abs(((ca ushr it) and 0xff) - ((cb ushr it) and 0xff)) > CHANNEL_TOLERANCE }) count++
    }
    return count
  }

  private companion object {
    const val WIDTH = 720
    const val HEIGHT = 640
    const val DENSITY = 2f
    const val FRAME_NANOS = 16_666_667L
    const val CHANNEL_TOLERANCE = 48
    const val MAX_DIFFERING = 0.01
  }
}
