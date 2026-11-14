package ee.schimke.livery.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.compose.RcDisplay
import ee.schimke.composeai.rcplayer.compose.rememberRcPreparedDocument
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
import ee.schimke.livery.client.PlanResult
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
import kotlin.math.ceil
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `liveryFeedProfile`: a fling down a real `LazyColumn` of [ITEMS] sample cards (`banner-classic`,
 * `poll-card`), drawn three ways with the same data, size and density:
 * - **livery**: a [LiverySlot] per card, every slot prepared before the list shows;
 * - **rc**: the same prepared documents and values played by [RcComposePlayer] alone;
 * - **compose**: the Compose UI twins (livery-baselineprofile/twins), which TwinRenderParityTest holds
 *   to the templates' pixels.
 *
 * Each frame scrolls the list by [STEP] pixels and renders it (composition, layout and a software
 * draw on Compose Desktop): its wall time is what the cards arriving that frame cost. With
 * `-Dlivery.feed.contentType=true` every item names its template as its content type, so the list
 * reuses one card's nodes for the next card of the same template. Arguments: the output directory
 * and the built sample catalog.
 */
object FeedProfileMain {
  @JvmStatic
  fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "build/profile" }).also { it.mkdirs() }
    val samples = File(args.getOrElse(1) { error("pass the sample catalog directory") })
    val kinds = System.getProperty("livery.feed.lanes")?.split(',') ?: listOf("compose", "rc", "livery")
    val reuse = System.getProperty("livery.feed.contentType")?.split(',')?.map { it.toBoolean() } ?: listOf(false, true)
    val lanes = reuse.flatMap { r -> kinds.map { Lane(it, r) } }
    val feed = Feed(samples)
    lanes.forEach { feed.fling(it.kind, it.reuse, WARMUP_FRAMES) }
    val samplesByLane = lanes.associateWith { mutableListOf<Frame>() }
    repeat(ROUNDS) { round ->
      lanes.indices.forEach { i ->
        val lane = lanes[(i + round) % lanes.size]
        samplesByLane.getValue(lane) += feed.fling(lane.kind, lane.reuse, FRAMES / ROUNDS)
      }
    }
    val report = buildString {
      appendLine("# Feed fling: Livery, the player alone and Compose UI")
      appendLine()
      appendLine("- ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}, ${Runtime.getRuntime().availableProcessors()} CPUs; Compose Desktop software raster")
      appendLine("- $ITEMS cards (banner-classic, poll-card), ${WIDTH}×$HEIGHT px at density $DENSITY, $STEP px a frame, $FRAMES frames per lane")
      appendLine("- *Frame*: a scrolled frame's wall time. *Card work*: that frame minus a redraw of the same")
      appendLine("  screen with nothing scrolled, measured right after it: what the arriving cards cost beyond")
      appendLine("  repainting (composition, measure and layout, and their first draw).")
      appendLine()
      appendLine("| Lane | Reuse | Frame mean | Frame p90 | Card work mean | Card work p90 | Card work vs Compose UI |")
      appendLine("| --- | --- | ---: | ---: | ---: | ---: | ---: |")
      for ((lane, frames) in samplesByLane) {
        val compose = samplesByLane[Lane("compose", lane.reuse)]?.map { it.work }?.average()
        fun stats(xs: List<Long>): Pair<Double, Double> {
          val sorted = xs.sorted()
          return xs.average() / 1e6 to sorted[(ceil(0.9 * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)] / 1e6
        }
        val (fm, f90) = stats(frames.map { it.total })
        val (wm, w90) = stats(frames.map { it.work })
        appendLine("| ${lane.kind} | ${if (lane.reuse) "yes" else "no"} | ${"%.2f".format(fm)} ms | ${"%.2f".format(f90)} ms | ${"%.2f".format(wm)} ms | ${"%.2f".format(w90)} ms | ${compose?.let { "%.2f×".format(frames.map { f -> f.work }.average() / it) } ?: "–"} |")
      }
    }
    File(out, "feed.md").writeText(report)
    println(report)
  }

  private data class Lane(val kind: String, val reuse: Boolean)

  /** A scrolled frame's wall time, and that minus a redraw of the same screen with nothing new. */
  private class Frame(val total: Long, val work: Long)

  /** The cards, their templates and a host that has everything prepared before the list shows. */
  private class Feed(samples: File) {
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
    private val banner = template(samples, "banner-classic", "promo-banner")
    private val poll = template(samples, "poll-card", "poll")
    val cards = List(ITEMS) { i -> if (i % 2 == 0) Card.banner(i, banner) else Card.poll(i, poll) }
    private val display = RcDisplay(DENSITY)

    fun fling(lane: String, contentTypes: Boolean, frames: Int): List<Frame> {
      // A fresh host each run, as a screen opening has: nothing composed yet, everything prepared.
      val host = LiveryHost(
        repository, ActionRouter(emptyMap(), { false }, { }, clock), { }, clock,
        resources = LiveryResources(repository, maxPlans = ITEMS),
        flattenLayout = lane == "livery-flat",
      )
      host.resources.display = display
      runBlocking {
        when (lane) {
          "livery", "livery-flat" -> cards.forEachIndexed { i, c -> host.resources.prepareSlot(SLOT, i) { PlanResult.Ready(c.plan) } }
          "rc", "rc-defer" -> listOf(banner, poll).forEach { host.resources.readyToDraw(it.first, display) }
        }
      }
      val list = LazyListState()
      val scene = ImageComposeScene(WIDTH, HEIGHT, Density(DENSITY)) {
        LazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(12.dp)) {
          items(cards.size, key = { it }, contentType = { if (contentTypes) cards[it].plan.template.entry.designId else null }) { i ->
            val card = cards[i]
            val modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            when (lane) {
              "livery", "livery-flat" -> LiverySlot(key = i, host = host, modifier = modifier, slotId = SLOT) { PlanResult.Ready(card.plan) }
              "rc", "rc-defer" -> {
                val prepared = rememberRcPreparedDocument(card.plan.template.bytes, host.preparation)
                if (prepared != null) {
                  val values = remember(card) { mutableStateMapOf<String, ee.schimke.composeai.rcplayer.runtime.RcNamedValue>().apply { putAll(card.plan.bindings.toPlayerValues()) } }
                  androidx.compose.runtime.CompositionLocalProvider(ee.schimke.composeai.rcplayer.compose.LocalRcDeferLayoutAnimations provides (lane == "rc-defer")) {
                    RcComposePlayer(prepared, modifier, namedValues = values)
                  }
                }
              }
              else -> card.twin(modifier)
            }
          }
        }
      }
      try {
        var time = 0L
        scene.render(time).close()
        return List(frames) {
          // Back to the top once the end is near, so every frame brings cards on.
          if (list.firstVisibleItemIndex > ITEMS - 12) { list.requestScrollToItem(0); scene.render(time).close() }
          list.dispatchRawDelta(STEP)
          time += FRAME_NANOS
          val start = System.nanoTime()
          scene.render(time).close()
          val total = System.nanoTime() - start
          // The same screen again, nothing scrolled: what repainting it costs on its own.
          time += FRAME_NANOS
          val redrawStart = System.nanoTime()
          scene.render(time).close()
          val redraw = System.nanoTime() - redrawStart
          Frame(total, (total - redraw).coerceAtLeast(0))
        }
      } finally {
        scene.close()
      }
    }

    private fun template(samples: File, design: String, contractFile: String): Pair<LoadedTemplate, Contract> {
      val contract = TemplateRepository.DefaultJson.decodeFromString(Contract.serializer(), File(samples, "contracts/$contractFile.json").readText())
      return LoadedTemplate(
        entry = TemplateEntry(design, 0, design, contract.ref, Target(Surfaces.IN_APP, 7)),
        design = null,
        bytes = File(samples, "designs/$design/in-app.rc").readBytes(),
        origin = LoadedTemplate.Origin.Bundled,
        contract = contract,
      ) to contract
    }
  }

  /** One card: its plan (what Livery and the player play) and its Compose UI twin. */
  private class Card(val plan: RenderPlan, val twin: @androidx.compose.runtime.Composable (Modifier) -> Unit) {
    companion object {
      fun banner(i: Int, t: Pair<LoadedTemplate, Contract>): Card {
        val data = BannerData("Try Premium free #$i", "Offline downloads and no ads for 30 days, for card $i.", "Start trial")
        val json = JsonObject(mapOf("headline" to JsonPrimitive(data.headline), "body" to JsonPrimitive(data.body), "cta" to JsonPrimitive(data.cta)))
        return Card(plan(t, json)) { BannerTwin(data, it) }
      }

      fun poll(i: Int, t: Pair<LoadedTemplate, Contract>): Card {
        val data = PollData(
          "Which language do you write your UI in? (#$i)",
          "${100 + i} votes",
          listOf(PollOption("Kotlin", 0.52f, "52%", "✓"), PollOption("Swift", 0.27f, "27%"), PollOption("TypeScript", 0.21f, "21%")),
        )
        val json = JsonObject(
          mapOf(
            "question" to JsonPrimitive(data.question),
            "summary" to JsonPrimitive(data.summary),
            "options" to JsonArray(data.options.map { o ->
              JsonObject(buildMap {
                put("label", JsonPrimitive(o.label)); put("share", JsonPrimitive(o.share)); put("result", JsonPrimitive(o.result))
                if (o.mark.isNotEmpty()) put("mark", JsonPrimitive(o.mark))
              })
            }),
          ),
        )
        return Card(plan(t, json)) { PollTwin(data, it) }
      }

      private fun plan(t: Pair<LoadedTemplate, Contract>, json: JsonObject): RenderPlan {
        val bound = ContractBinder.bind(t.second, json) as? BindResult.Ok ?: error("${t.first.entry.designId}: ${ContractBinder.bind(t.second, json)}")
        return RenderPlan(t.first, t.second, bound.bindings)
      }
    }
  }

  private const val ITEMS = 200
  private const val WIDTH = 720
  private const val HEIGHT = 1600
  private const val DENSITY = 2f
  private const val STEP = 60f
  private const val FRAME_NANOS = 16_666_667L
  private const val WARMUP_FRAMES = 300
  private const val FRAMES = 2000
  private const val ROUNDS = 5
  private const val SLOT = "feed"
}
