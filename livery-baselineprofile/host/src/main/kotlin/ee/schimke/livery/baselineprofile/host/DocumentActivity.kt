package ee.schimke.livery.baselineprofile.host

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.compose.RcPreparation
import ee.schimke.composeai.rcplayer.compose.rememberRcPreparedDocument
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.livery.client.ActionRouter
import ee.schimke.livery.protocol.TemplateEvent
import ee.schimke.livery.compose.LiverySlot
import ee.schimke.livery.client.EventSink
import ee.schimke.livery.client.AndroidBundled
import ee.schimke.livery.client.Clock
import ee.schimke.livery.client.FetchResult
import ee.schimke.livery.client.Fetcher
import ee.schimke.livery.client.JavaCrypto
import ee.schimke.livery.client.MemoryBlobStore
import ee.schimke.livery.client.MemoryKeyValueStore
import ee.schimke.livery.client.PlanResult
import ee.schimke.livery.client.SlotPlanner
import ee.schimke.livery.client.StaticSource
import ee.schimke.livery.client.TemplateQuery
import ee.schimke.livery.client.TemplateRepository
import ee.schimke.livery.client.TrustPolicy
import ee.schimke.livery.compose.LiveryHost
import ee.schimke.livery.compose.LiveryResources
import ee.schimke.livery.compose.startup
import ee.schimke.livery.compose.toPlayerValues
import ee.schimke.livery.protocol.PlayerCapabilities
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.twins.PollData
import ee.schimke.livery.twins.PollOption
import ee.schimke.livery.twins.PollTwin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One template filling the screen, for the document benchmarks (`:livery-benchmark`'s
 * `DocumentBenchmark`): the sample catalog's `poll-card`, drawn the way `lane` says. Tapping the
 * document (tag `document`) pushes new data, as a host does when a value updates; it has to repaint
 * and relayout.
 *
 * Every lane installs the splash screen (the activity's starting theme), so all of them run the same
 * activity code. The Livery lanes draw a [LiverySlot], as an app writes one, and those that start
 * up do it as getting-started tells an app to: [startup] in `onCreate`, with the splash screen kept
 * up until it is ready.
 *
 * Lanes:
 * - `livery`: a host with the shipped defaults, its first screen prepared by [startup];
 * - `livery-nostartup`: the same without [startup]: the slot plans when it first composes, and the
 *   first frame waits for it within [LiveryHost.firstFrameBudgetMillis];
 * - `livery-incomposition`: [RcPreparation.InComposition] and no [startup] (the document is prepared
 *   on the main thread in the first composition; nothing was prepared ahead);
 * - `livery-nodefer`: `livery` but `deferLayoutAnimations = false` (the lookahead pass runs on
 *   every frame; flat layout needs the deferral, so it is off too);
 * - `livery-noflat`: `livery` but `flattenLayout = false` (every component its own composable);
 * - `rc`: the prepared document played by [RcComposePlayer] with nothing of Livery around it;
 * - `compose`: the Compose UI twin, which TwinRenderParityTest holds to the template's pixels. It
 *   builds nothing of Livery: no repository, so no catalog read either.
 */
class DocumentActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    val splash = installSplashScreen()
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val lane = intent.getStringExtra(LANE) ?: LIVERY
    // The twin builds nothing of Livery: the comparison charges Livery's catalog and preparation to
    // the Livery lanes alone.
    val livery = if (lane == COMPOSE) null else Livery(lane)
    if (livery != null && lane in STARTUP_LANES) {
      // As getting-started has it: the first screen prepared by Livery's startup (on a worker,
      // overlapping the activity's creation), and the splash screen kept up until it is ready.
      val startup = livery.host.resources.startup(lifecycleScope) {
        prepareSlot(SLOT, 0) { livery.planner.forAppData(livery.query, json(pollData(0))) }
      }
      splash.setKeepOnScreenCondition { !startup.isReady }
    }

    setContent {
      var version by remember { mutableIntStateOf(0) }
      // The data cycles through VARIANTS values, as a host's data updates. The Livery lanes draw it
      // in a LiverySlot, as an app does: each change is planned there, and the slot keeps each
      // variant's answer (its slotId), so after the first round a change draws an answer it has.
      // The first answer is prepared by Livery's startup (the splash screen waits for it), or, in the
      // lanes without one, the first frame waits for it within the host's first-frame budget.
      val variant = version % VARIANTS
      val data = remember(variant) { pollData(variant) }
      // The player alone has no slot: it plans the same data, then plays the document.
      var rcPlan by remember { mutableStateOf<PlanResult.Ready?>(null) }
      // Set once the player has composed a prepared document: a plan alone may still be preparing.
      var rcComposed by remember { mutableStateOf(false) }
      if (lane == RC) {
        LaunchedEffect(variant) { rcPlan = livery!!.planner.forAppData(livery.query, json(data)) as? PlanResult.Ready }
      }
      // Drawn: the twin from its first frame; the player once it composed its document; a Livery lane on
      // Livery's own Impression event, after the template's first frame.
      val contentDrawn = when (lane) {
        COMPOSE -> true
        RC -> rcComposed
        else -> livery!!.impression.value
      }
      var drawn by remember { mutableStateOf(false) }
      LaunchedEffect(contentDrawn) {
        if (contentDrawn && !drawn) {
          // After the first frame that has the document in it: the start-up benchmark's end point.
          // The twin and the player know only that they composed it, so they wait for that frame;
          // Livery's Impression already comes after the template's first frame, so it reports now.
          if (lane == COMPOSE || lane == RC) withFrameNanos { }
          drawn = true
          reportFullyDrawn()
          // StartupTimingMetric ends "full display" at the first frame drawn after this report. A
          // screen that is already drawn and still would otherwise wait for whatever next
          // invalidates it, which is nothing to do with the document.
          window.decorView.invalidate()
        }
      }
      // The same frame for every lane: padded, the document at the top and as wide as the screen. The
      // template fills the width and takes its own height, so the twin is given no more than that.
      Box(
        // One semantics block: a second testTag on the same node would lose to the first. The tag
        // says "document" only once it has been drawn, which is what the benchmarks wait for.
        Modifier.fillMaxSize()
          .safeDrawingPadding()
          .semantics { testTagsAsResourceId = true; testTag = if (drawn) "document" else "document-screen" }
          .clickable { version++ }
          .padding(16.dp),
      ) {
        val modifier = Modifier.fillMaxWidth()
        when (lane) {
          COMPOSE -> PollTwin(data, modifier)
          RC -> rcPlan?.plan?.let { p ->
            val array = remember(p.template.bytes) { p.template.bytes.toByteArray() }
            val prepared = rememberRcPreparedDocument(array, livery!!.host.preparation) ?: return@let
            val values = remember { mutableStateMapOf<String, RcNamedValue>() }
            values.putAll(p.bindings.toPlayerValues())
            RcComposePlayer(prepared, modifier, namedValues = values)
            SideEffect { rcComposed = true }
          }
          else -> LiverySlot(key = variant, host = livery!!.host, modifier = modifier, slotId = SLOT) {
            livery.planner.forAppData(livery.query, json(data))
          }
        }
      }
    }
  }

  /** Livery for a lane that uses it: the bundled sample catalog, offline, and a host configured as [lane] says. */
  private inner class Livery(lane: String) {
    private val clock = Clock { System.currentTimeMillis() }
    private val repository = TemplateRepository(
      StaticSource("https://catalog.invalid/manifest.json"),
      Fetcher { _, _ -> FetchResult.Failed(503, "offline") },
      JavaCrypto,
      TrustPolicy.AllowUnsigned,
      MemoryBlobStore(),
      MemoryKeyValueStore(),
      clock,
      listOf(Capabilities),
      bundled = AndroidBundled.fromAssets(this@DocumentActivity),
    )
    private val resources = LiveryResources(repository, maxPlans = 4)
    private val actions = ActionRouter(emptyMap(), { false }, {}, clock)

    /** Livery's own signal that a template's first frame was drawn, as an app's analytics sees it. */
    val impression = mutableStateOf(false)
    private val events = EventSink { if (it.type == TemplateEvent.Type.Impression) impression.value = true }
    val host = when (lane) {
      LIVERY_IN_COMPOSITION -> LiveryHost(repository, actions, events, clock, resources = resources, preparation = RcPreparation.InComposition)
      LIVERY_NO_DEFER -> LiveryHost(repository, actions, events, clock, resources = resources, deferLayoutAnimations = false)
      LIVERY_NO_FLAT -> LiveryHost(repository, actions, events, clock, resources = resources, flattenLayout = false)
      else -> LiveryHost(repository, actions, events, clock, resources = resources)
    }
    val planner = SlotPlanner(repository, {}, clock)
    val query = TemplateQuery("social/poll@1", Capabilities, designId = "poll-card")
  }

  private fun pollData(version: Int) = PollData(
    "Which language do you write your UI in?",
    "${100 + version} votes",
    listOf(
      PollOption("Kotlin", 0.52f, "${52 + version % 10}%", "✓"),
      PollOption("Swift", 0.27f, "27%"),
      PollOption("TypeScript", 0.21f, "21%"),
    ),
  )

  private fun json(data: PollData) = JsonObject(
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

  companion object {
    const val LANE = "lane"
    private const val VARIANTS = 10
    const val LIVERY = "livery"
    const val LIVERY_NO_STARTUP = "livery-nostartup"
    const val LIVERY_IN_COMPOSITION = "livery-incomposition"
    const val LIVERY_NO_DEFER = "livery-nodefer"
    const val LIVERY_NO_FLAT = "livery-noflat"
    const val RC = "rc"
    const val COMPOSE = "compose"
    private const val SLOT = "document"
    /** The lanes that prepare their first screen with Livery's startup; the others plan as they compose. */
    private val STARTUP_LANES = setOf(LIVERY, LIVERY_NO_DEFER, LIVERY_NO_FLAT)
    private val Capabilities = PlayerCapabilities(Surfaces.IN_APP, maxRcApiLevel = 7, profiles = setOf("androidx"))
  }
}
