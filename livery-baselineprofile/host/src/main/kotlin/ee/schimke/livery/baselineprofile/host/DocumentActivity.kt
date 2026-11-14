package ee.schimke.livery.baselineprofile.host

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import ee.schimke.livery.compose.LiveryTemplate
import ee.schimke.livery.compose.rememberLiveryDisplay
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
 * - `rc`: the prepared document played by [RcComposePlayer] with nothing of Livery around it;
 * - `compose`: the Compose UI twin, which TwinRenderParityTest holds to the template's pixels.
 */
class DocumentActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    hideSystemBars()
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
    val host = if (lane == LIVERY_IN_COMPOSITION) {
      LiveryHost(repository, ActionRouter(emptyMap(), { false }, {}, clock), {}, clock, resources = resources, preparation = RcPreparation.InComposition)
    } else {
      LiveryHost(repository, ActionRouter(emptyMap(), { false }, {}, clock), {}, clock, resources = resources)
    }
    val planner = SlotPlanner(repository, {}, clock)
    val query = TemplateQuery("social/poll@1", Capabilities, designId = "poll-card")

    setContent {
      val display = rememberLiveryDisplay()
      var version by remember { mutableIntStateOf(0) }
      val data = remember(version) { pollData(version) }
      // The plan for this data: what a host binds when its value changes.
      var plan by remember { mutableStateOf<PlanResult.Ready?>(null) }
      var ready by remember { mutableStateOf(lane == COMPOSE || lane == LIVERY_IN_COMPOSITION) }
      LaunchedEffect(display, version) {
        if (lane != COMPOSE) {
          resources.display = display
          (planner.forAppData(query, json(data)) as? PlanResult.Ready)?.let { plan = it }
          if (lane == LIVERY || lane == RC) plan?.plan?.template?.let { resources.readyToDraw(it, display) }
        }
        ready = true
      }
      Column(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true; testTag = "document-screen" }) {
        Box(Modifier.fillMaxSize().clickable { version++ }.semantics { testTag = "document" }) {
          if (!ready) return@Box
          val modifier = Modifier.fillMaxSize().padding(16.dp)
          when (lane) {
            COMPOSE -> PollTwin(data, modifier)
            RC -> plan?.plan?.let { p ->
              val prepared = rememberRcPreparedDocument(p.template.bytes, host.preparation) ?: return@let
              val values = remember { mutableStateMapOf<String, RcNamedValue>() }
              values.putAll(p.bindings.toPlayerValues())
              RcComposePlayer(prepared, modifier, namedValues = values)
            }
            else -> plan?.plan?.let { LiveryTemplate(it, host, modifier) }
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
    const val LIVERY = "livery"
    const val LIVERY_IN_COMPOSITION = "livery-incomposition"
    const val RC = "rc"
    const val COMPOSE = "compose"
    private val Capabilities = PlayerCapabilities(Surfaces.IN_APP, maxRcApiLevel = 7, profiles = setOf("androidx"))
  }
}
