package ee.schimke.livery.baselineprofile.host

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * Lanes, each with everything the document needs in memory before its first frame unless it is the
 * lane that measures the opposite:
 * - `livery`: [LiveryTemplate] on a host with the shipped defaults, prepared at startup;
 * - `livery-incomposition`: the same, with [RcPreparation.InComposition] (the document is prepared
 *   on the main thread in the first composition; nothing was prepared ahead);
 * - `livery-nodefer`: the defaults but `deferLayoutAnimations = false` (the lookahead pass runs on
 *   every frame; flat layout needs the deferral, so it is off too);
 * - `livery-noflat`: the defaults but `flattenLayout = false` (every component its own composable);
 * - `rc`: the prepared document played by [RcComposePlayer] with nothing of Livery around it;
 * - `compose`: the Compose UI twin, which TwinRenderParityTest holds to the template's pixels.
 */
class DocumentActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val lane = intent.getStringExtra(LANE) ?: LIVERY
    val clock = Clock { System.currentTimeMillis() }
    val repository = TemplateRepository(
      StaticSource("https://catalog.invalid/manifest.json"),
      Fetcher { _, _ -> FetchResult.Failed(503, "offline") },
      JavaCrypto,
      TrustPolicy.AllowUnsigned,
      MemoryBlobStore(),
      MemoryKeyValueStore(),
      clock,
      listOf(Capabilities),
      bundled = AndroidBundled.fromAssets(this),
    )
    val resources = LiveryResources(repository, maxPlans = 4)
    val actions = ActionRouter(emptyMap(), { false }, {}, clock)
    // Livery's own signal that a template's first frame was drawn, as an app's analytics sees it.
    val impression = mutableStateOf(false)
    val events = EventSink { if (it.type == TemplateEvent.Type.Impression) impression.value = true }
    val host = when (lane) {
      LIVERY_IN_COMPOSITION -> LiveryHost(repository, actions, events, clock, resources = resources, preparation = RcPreparation.InComposition)
      LIVERY_NO_DEFER -> LiveryHost(repository, actions, events, clock, resources = resources, deferLayoutAnimations = false)
      LIVERY_NO_FLAT -> LiveryHost(repository, actions, events, clock, resources = resources, flattenLayout = false)
      else -> LiveryHost(repository, actions, events, clock, resources = resources)
    }
    val planner = SlotPlanner(repository, {}, clock)
    val query = TemplateQuery("social/poll@1", Capabilities, designId = "poll-card")

    setContent {
      var version by remember { mutableIntStateOf(0) }
      // The data cycles through VARIANTS values, as a host's data updates. The Livery lanes draw it
      // in a LiverySlot, as an app does: each change is planned there, and the slot keeps each
      // variant's answer (its slotId), so after the first round a change draws an answer it has.
      // The first frame waits for the first answer within the host's first-frame budget, as any
      // app's does; nothing here holds frames or plans ahead on Livery's behalf.
      val variant = version % VARIANTS
      val data = remember(variant) { pollData(variant) }
      // The player alone has no slot: it plans the same data, then plays the document.
      var rcPlan by remember { mutableStateOf<PlanResult.Ready?>(null) }
      if (lane == RC) {
        LaunchedEffect(variant) { rcPlan = planner.forAppData(query, json(data)) as? PlanResult.Ready }
      }
      // Drawn: the twin from its first frame; the player once it has a plan; a Livery lane on
      // Livery's own Impression event, after the template's first frame.
      val contentDrawn = when (lane) {
        COMPOSE -> true
        RC -> rcPlan != null
        else -> impression.value
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
            val prepared = rememberRcPreparedDocument(p.template.bytes, host.preparation) ?: return@let
            val values = remember { mutableStateMapOf<String, RcNamedValue>() }
            values.putAll(p.bindings.toPlayerValues())
            RcComposePlayer(prepared, modifier, namedValues = values)
          }
          else -> LiverySlot(key = variant, host = host, modifier = modifier, slotId = SLOT) {
            planner.forAppData(query, json(data))
          }
        }
      }
    }
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
    const val LIVERY_IN_COMPOSITION = "livery-incomposition"
    const val LIVERY_NO_DEFER = "livery-nodefer"
    const val LIVERY_NO_FLAT = "livery-noflat"
    const val RC = "rc"
    const val COMPOSE = "compose"
    private const val SLOT = "document"
    private val Capabilities = PlayerCapabilities(Surfaces.IN_APP, maxRcApiLevel = 7, profiles = setOf("androidx"))
  }
}
