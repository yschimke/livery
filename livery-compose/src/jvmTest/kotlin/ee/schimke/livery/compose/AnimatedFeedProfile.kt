package ee.schimke.livery.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.tooling.setObserver
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rcplayer.compose.LocalRcDeferLayoutAnimations
import ee.schimke.composeai.rcplayer.compose.LocalRcFlattenLayout
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.compose.RcDisplay
import ee.schimke.composeai.rcplayer.compose.ExperimentalRcPlayerApi
import ee.schimke.composeai.rcplayer.compose.rememberRcPreparedDocument
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
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
import ee.schimke.livery.twins.PollData
import ee.schimke.livery.twins.PollOption
import ee.schimke.livery.twins.PollTwin
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString.Companion.toByteString

/**
 * `liveryAnimatedProfile`: the benchmark's animated feed lane on the JVM. A `LazyColumn` of `poll-card`s whose numbers
 * change every [TICK_FRAMES] frames (a second at 60 fps), as `FeedActivity`'s `animateMs=1000` does, drawn by:
 * - **compose** / **compose-snap**: the Compose UI twin, its bars eased (`animateShares`) or not;
 * - **rc** / **rc-nodefer**: the player alone, its values map updated in place, lookahead deferred or not;
 * - **livery** / **livery-nodefer**: a [LiverySlot] per card, keyed by its data as the benchmark's are.
 *
 * Each frame scrolls by [STEP] px (0 with `-Dlivery.feed.step=0`: updates only; `-Dlivery.feed.updates=false`: scrolling
 * only). Besides frame times it counts, through Compose's composition tracer, every composable body that ran (was not
 * skipped), by name and by frame after an update: what an update recomposes, and for how long.
 * `-Dlivery.feed.debug=true` prints the snapshot state written on each frame around the first update: what keeps a
 * player recomposing.
 *
 * Narrowing an update down (`-P` on the Gradle task, as the system properties here):
 * - `livery.feed.skipTicks=N` counts the frames after an update only from the N+1th update on: the steady state of a
 *   card, without the first changes that switch a deferred lookahead pass on and off;
 * - `livery.feed.phases=true` prints, for frames 0..[PHASE_DETAIL] after an update, which composables ran and which
 *   state was written: which frame of the update does what;
 * - `livery.feed.invalidations=true` prints, for one update after the skipped ones, which state invalidated which
 *   composable (Compose's composition observer), which state the player's top level read, and where (the stack) state
 *   was written outside composition: why a frame recomposed. It is how the top level was found reading every version
 *   it published (rc-players `RcValueVersions.publish`).
 *
 * The `rc` lanes write the card's values as it composes, as `LiveryTemplate` does; `rc-flat` and `rc-noflat` split
 * `rc`'s two settings (the flat layout, the deferred lookahead pass) to tell them apart.
 */
@OptIn(
  InternalComposeTracingApi::class,
  ExperimentalRcPlayerApi::class,
  androidx.compose.runtime.InternalComposeApi::class,
  androidx.compose.runtime.ExperimentalComposeRuntimeApi::class,
)
object AnimatedFeedProfileMain {
  private val counts = HashMap<String, Int>()
  private var counting = false
  private var bodies = 0
  private var updates = true
  /** Bodies run at each frame of an update's cycle (frame 0: the frame after the update), summed over updates. */
  private val byPhase = LongArray(TICK_FRAMES)
  /** With `-Dlivery.feed.phases=true`: which composables ran, and which state was written, at frames 1..[PHASE_DETAIL]. */
  private val PHASES = System.getProperty("livery.feed.phases") == "true"
  private const val PHASE_DETAIL = 4
  private var phase = 0
  private val phaseCounts = Array(PHASE_DETAIL + 1) { HashMap<String, Int>() }
  private val phaseWrites = Array(PHASE_DETAIL + 1) { HashMap<String, Int>() }

  @JvmStatic
  fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "build/profile" }).also { it.mkdirs() }
    val samples = File(args.getOrElse(1) { error("pass the sample catalog directory") })
    val lanes = System.getProperty("livery.feed.lanes")?.split(',') ?: listOf("compose", "compose-snap", "rc", "rc-nodefer", "livery", "livery-nodefer")
    val step = System.getProperty("livery.feed.step")?.toFloat() ?: STEP
    updates = System.getProperty("livery.feed.updates")?.toBoolean() ?: true
    Composer.setTracer(object : CompositionTracer {
      override fun isTraceInProgress() = counting
      override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
        val name = info.substringBefore(" (")
        pendingScope?.let { scopeNames.putIfAbsent(it, name); pendingScope = null }
        counts[name] = (counts[name] ?: 0) + 1
        if (PHASES && phase in 1..PHASE_DETAIL) phaseCounts[phase].merge(name, 1, Int::plus)
        bodies++
      }
      override fun traceEventEnd() {}
    })
    val feed = Feed(samples)
    lanes.forEach { feed.run(it, step, WARMUP_FRAMES) }
    val results = lanes.associateWith { mutableListOf<Long>() }
    val composed = lanes.associateWith { HashMap<String, Int>() }
    val phases = lanes.associateWith { LongArray(TICK_FRAMES) }
    repeat(ROUNDS) { round ->
      lanes.indices.forEach { i ->
        val lane = lanes[(i + round) % lanes.size]
        counts.clear()
        counting = true
        byPhase.fill(0)
        phaseCounts.forEach { it.clear() }
        phaseWrites.forEach { it.clear() }
        results.getValue(lane) += feed.run(lane, step, FRAMES / ROUNDS)
        counting = false
        byPhase.forEachIndexed { k, v -> phases.getValue(lane)[k] += v }
        counts.forEach { (k, v) -> composed.getValue(lane).merge(k, v, Int::plus) }
        if (PHASES && round == ROUNDS - 1) {
          val perTick = (FRAMES / ROUNDS / TICK_FRAMES).coerceAtLeast(1)
          println("== $lane")
          for (k in 0..PHASE_DETAIL) {
            println("  frame $k: ran " + phaseCounts[k].entries.sortedByDescending { it.value }.take(14).joinToString { "${it.key.substringAfterLast('.')}=${it.value / perTick}" })
            println("  frame $k: wrote " + phaseWrites[k].entries.sortedByDescending { it.value }.take(10).joinToString { "${it.key}=${it.value / perTick}" })
          }
        }
      }
    }
    val ticks = FRAMES / TICK_FRAMES
    val report = buildString {
      appendLine("# Animated feed: poll cards updating every $TICK_FRAMES frames")
      appendLine()
      appendLine("- ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}; Compose Desktop software raster")
      appendLine("- ${WIDTH}×$HEIGHT px at density $DENSITY, $step px a frame, $FRAMES frames per lane ($ticks updates)")
      appendLine()
      appendLine("| Lane | Frame mean | p50 | p90 | p99 | Bodies run per update | Of them, Lookahead scopes |")
      appendLine("| --- | ---: | ---: | ---: | ---: | ---: | ---: |")
      for ((lane, frames) in results) {
        val sorted = frames.sorted()
        fun p(q: Double) = sorted[(ceil(q * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)] / 1e6
        val c = composed.getValue(lane)
        val lookahead = c.filterKeys { it.endsWith("LookaheadScope") }.values.sum()
        appendLine("| $lane | ${"%.2f".format(frames.average() / 1e6)} ms | ${"%.2f".format(p(.5))} | ${"%.2f".format(p(.9))} | ${"%.2f".format(p(.99))} | ${c.values.sum() / ticks} | ${lookahead / ticks} |")
      }
      appendLine()
      appendLine("Bodies run at each frame after an update (frame 0 is the update's), per update:")
      appendLine()
      for ((lane, ph) in phases) appendLine("- $lane: " + ph.take(24).joinToString(" ") { (it / ticks).toString() } + " … rest of the second: ${ph.drop(24).sum() / ticks}")
      for ((lane, c) in composed) {
        appendLine()
        appendLine("## $lane: most-run composables, per update")
        appendLine()
        c.entries.sortedByDescending { it.value }.take(TOP).forEach { (k, v) -> appendLine("- ${"%.1f".format(v.toDouble() / ticks)} $k") }
      }
    }
    if (INVALIDATIONS) invalidations.forEach { (k, v) -> println("invalidation ${v}x $k") }
    File(out, "animated.md").writeText(report)
    println(report)
  }

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
    private val contract = TemplateRepository.DefaultJson.decodeFromString(Contract.serializer(), File(samples, "contracts/poll.json").readText())
    private val poll = LoadedTemplate(
      entry = TemplateEntry("poll-card", 0, "poll-card", contract.ref, Target(Surfaces.IN_APP, 7)),
      design = null,
      bytes = File(samples, "designs/poll-card/in-app.rc").readBytes().toByteString(),
      origin = LoadedTemplate.Origin.Bundled,
      contract = contract,
    )
    private val display = RcDisplay(DENSITY)
    private val plans = HashMap<Pair<Int, Int>, RenderPlan>()

    /** Card [i] at update [tick]: the numbers move as `FeedActivity`'s `Card.Poll(index, version)` does. */
    fun data(i: Int, tick: Int): PollData {
      val kotlin = if (tick == 0) 0.52f else 0.40f + (tick % 10) * 0.02f
      return PollData(
        "Which language do you write your UI in? (#$i)",
        "${100 + i + tick} votes",
        listOf(PollOption("Kotlin", kotlin, "${(kotlin * 100).roundToInt()}%", "✓"), PollOption("Swift", 0.27f, "27%"), PollOption("TypeScript", 0.21f, "21%")),
      )
    }

    fun plan(i: Int, tick: Int): RenderPlan = synchronized(plans) {
      plans.getOrPut(i to tick) {
        val d = data(i, tick)
        val json = JsonObject(
          mapOf(
            "question" to JsonPrimitive(d.question),
            "summary" to JsonPrimitive(d.summary),
            "options" to JsonArray(d.options.map { o ->
              JsonObject(buildMap {
                put("label", JsonPrimitive(o.label)); put("share", JsonPrimitive(o.share)); put("result", JsonPrimitive(o.result))
                if (o.mark.isNotEmpty()) put("mark", JsonPrimitive(o.mark))
              })
            }),
          ),
        )
        val bound = ContractBinder.bind(contract, json) as? BindResult.Ok ?: error("poll: ${ContractBinder.bind(contract, json)}")
        RenderPlan(poll, contract, bound.bindings)
      }
    }

    fun run(lane: String, step: Float, frames: Int): List<Long> {
      val host = LiveryHost(
        repository, ActionRouter(emptyMap(), { false }, { }, clock), { }, clock,
        resources = LiveryResources(repository, maxPlans = ITEMS * 4),
        deferLayoutAnimations = lane != "livery-nodefer",
      )
      host.resources.display = display
      runBlocking {
        if (lane.startsWith("livery")) repeat(ITEMS) { i -> host.resources.prepareSlot(SLOT, i) { PlanResult.Ready(plan(i, 0)) } }
        else host.resources.readyToDraw(poll, display)
      }
      var tick by mutableIntStateOf(0)
      val list = LazyListState()
      val scene = ImageComposeScene(WIDTH, HEIGHT, Density(DENSITY)) {
        if (INVALIDATIONS) {
          val composition = androidx.compose.runtime.currentComposer.composition
          androidx.compose.runtime.DisposableEffect(composition) {
            val handle = composition.setObserver(compositionObserver)
            onDispose { handle?.dispose() }
          }
        }
        LazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(12.dp)) {
          items(ITEMS, key = { it }) { i ->
            val modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            val t = tick
            when (lane) {
              "compose", "compose-snap" -> PollTwin(data(i, t), modifier, animateShares = lane == "compose")
              "rc", "rc-nodefer", "rc-flat", "rc-noflat" -> {
                val prepared = rememberRcPreparedDocument(remember { poll.bytes.toByteArray() }, host.preparation)
                if (prepared != null) {
                  val pushed = plan(i, t).bindings.toPlayerValues()
                  val values = remember { mutableStateMapOf<String, RcNamedValue>().apply { putAll(pushed) } }
                  Snapshot.withoutReadObservation { pushed.forEach { (k, v) -> if (values[k] != v) values[k] = v } }
                  CompositionLocalProvider(LocalRcDeferLayoutAnimations provides (lane == "rc" || lane == "rc-noflat"), LocalRcFlattenLayout provides (lane == "rc" || lane == "rc-flat")) {
                    RcComposePlayer(prepared, modifier, namedValues = values)
                  }
                }
              }
              else -> LiverySlot(key = if (t > 0) i to t else i, host = host, modifier = modifier, slotId = SLOT, identity = i) { PlanResult.Ready(plan(i, t)) }
            }
          }
        }
      }
      try {
        var time = 0L
        scene.render(time).close()
        return List(frames) { f ->
          if (list.firstVisibleItemIndex > ITEMS - 12) { list.requestScrollToItem(0); scene.render(time).close() }
          if (updates && f % TICK_FRAMES == TICK_FRAMES - 1) tick++
          if (step > 0) list.dispatchRawDelta(step)
          time += FRAME_NANOS
          if (DEBUG && f in 58..80) {
            val written = HashMap<String, Int>()
            val h = androidx.compose.runtime.snapshots.Snapshot.registerApplyObserver { changed, _ -> changed.forEach { o -> val k = o.toString().replace(Regex("@[0-9a-f]+"), "").take(70); written[k] = (written[k] ?: 0) + 1 } }
            scene.render(time).close()
            h.dispose()
            println("frame $f: bodies=$bodies written=$written")
          }
          bodies = 0
          phase = (f + 1) % TICK_FRAMES
          explained = counting && (f + 1) / TICK_FRAMES == SKIP_TICKS + 1 && phase <= PHASE_DETAIL
          val observer = if (PHASES && counting && phase <= PHASE_DETAIL) Snapshot.registerApplyObserver { changed, _ ->
            changed.forEach { o -> phaseWrites[phase].merge(o.toString().anonymous().take(60), 1, Int::plus) }
          } else null
          val stacks = if (INVALIDATIONS && explained) Snapshot.registerGlobalWriteObserver { o ->
            invalidations.merge("frame $phase wrote ${o.toString().anonymous().take(40)} @ ${stack()}", 1, Int::plus)
          } else null
          val start = System.nanoTime()
          scene.render(time).close()
          val took = System.nanoTime() - start
          stacks?.dispose()
          observer?.dispose()
          if (f >= SKIP_TICKS * TICK_FRAMES) byPhase[(f + 1) % TICK_FRAMES] += bodies.toLong()
          took
        }
      } finally {
        scene.close()
      }
    }
  }

  private val INVALIDATIONS = System.getProperty("livery.feed.invalidations") == "true"
  private val invalidations = sortedMapOf<String, Int>()
  /** The update `livery.feed.invalidations` explains: the first after the skipped ones, frames 0..[PHASE_DETAIL]. */
  private var explained = false
  /** A scope entered and not yet named: the tracer names it by the next composable body it starts. */
  private var pendingScope: Any? = null
  private val scopeNames = java.util.WeakHashMap<Any, String>()

  private fun String.anonymous() = replace(Regex("@[0-9a-f]+"), "")

  private fun stack() = Throwable().stackTrace.drop(2).filter { ("rcplayer" in it.className || "livery" in it.className) && "AnimatedFeedProfile" !in it.className }.take(4)
    .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }

  private val compositionObserver = object : androidx.compose.runtime.tooling.CompositionObserver {
    override fun onBeginComposition(composition: androidx.compose.runtime.tooling.ObservableComposition) {}
    override fun onScopeEnter(scope: androidx.compose.runtime.RecomposeScope) { pendingScope = scope }
    override fun onReadInScope(scope: androidx.compose.runtime.RecomposeScope, value: Any) {
      if (explained && scopeNames[scope]?.endsWith("RcComposePlayerResolved") == true)
        invalidations.merge("frame $phase top level read ${value.toString().anonymous().take(50)} @ ${stack()}", 1, Int::plus)
    }
    override fun onScopeExit(scope: androidx.compose.runtime.RecomposeScope) {}
    override fun onEndComposition(composition: androidx.compose.runtime.tooling.ObservableComposition) {}
    override fun onScopeInvalidated(scope: androidx.compose.runtime.RecomposeScope, value: Any?) {
      if (explained) invalidations.merge("frame $phase invalidated ${scopeNames[scope] ?: "?"} by ${value.toString().anonymous().take(60)}", 1, Int::plus)
    }
    override fun onScopeDisposed(scope: androidx.compose.runtime.RecomposeScope) {}
  }
  private val SKIP_TICKS = System.getProperty("livery.feed.skipTicks")?.toInt() ?: 0
  private val DEBUG = System.getProperty("livery.feed.debug") == "true"
  private const val ITEMS = 200
  private const val WIDTH = 720
  private const val HEIGHT = 1600
  private const val DENSITY = 2f
  private const val STEP = 4f
  private const val TICK_FRAMES = 60
  private const val FRAME_NANOS = 16_666_667L
  private const val WARMUP_FRAMES = 300
  private const val FRAMES = 1800
  private const val ROUNDS = 3
  private const val TOP = 18
  private const val SLOT = "feed"
}
