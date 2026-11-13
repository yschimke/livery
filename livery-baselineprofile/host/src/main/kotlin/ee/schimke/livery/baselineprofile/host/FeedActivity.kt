package ee.schimke.livery.baselineprofile.host

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.livery.client.ActionRouter
import ee.schimke.livery.client.AndroidBundled
import ee.schimke.livery.client.Clock
import ee.schimke.livery.client.FetchResult
import ee.schimke.livery.client.Fetcher
import ee.schimke.livery.client.JavaCrypto
import ee.schimke.livery.client.MemoryBlobStore
import ee.schimke.livery.client.MemoryKeyValueStore
import ee.schimke.livery.client.SlotPlanner
import ee.schimke.livery.client.StaticSource
import ee.schimke.livery.client.TemplateQuery
import ee.schimke.livery.client.TemplateRepository
import ee.schimke.livery.client.TrustPolicy
import ee.schimke.livery.compose.LiveryHost
import ee.schimke.livery.compose.LiverySlot
import ee.schimke.livery.compose.rememberLiveryDisplay
import ee.schimke.livery.protocol.PlayerCapabilities
import ee.schimke.livery.protocol.Surfaces
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The feed `:livery-benchmark` flings: [ITEMS] cards, alternating the sample catalog's
 * `banner-classic` and `poll-card`, each with its own data. With `mode=livery` each card is a
 * [LiverySlot] playing the template; with `mode=compose` it is [BannerCard] or [PollCard], the same
 * design written directly in Compose UI. The same data, the same list, so the frame timings of the
 * two modes compare the two ways of drawing one card.
 */
class FeedActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val livery = intent.getStringExtra(MODE) != COMPOSE
    val items = List(ITEMS) { i -> if (i % 2 == 0) Card.Banner(i) else Card.Poll(i) }
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
    val host = LiveryHost(repository, ActionRouter(emptyMap(), { false }, {}, clock), {}, clock)
    val planner = SlotPlanner(repository, {}, clock)
    setContent {
      if (livery) {
        // A feed warms its templates for this display as its page arrives.
        val display = rememberLiveryDisplay()
        LaunchedEffect(display) { host.resources.warm(listOf(Card.Banner(0).query, Card.Poll(1).query), display) }
      }
      LazyColumn(
        Modifier.fillMaxSize().semantics { testTagsAsResourceId = true; testTag = "feed" },
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(items.size, key = { it }, contentType = { items[it]::class }) { index ->
          val card = items[index]
          val modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
          if (livery) {
            LiverySlot(key = index, host = host, modifier = modifier, slotId = "feed") { planner.forAppData(card.query, card.data) }
          } else when (card) {
            is Card.Banner -> BannerCard(card, modifier)
            is Card.Poll -> PollCard(card, modifier)
          }
        }
      }
    }
  }

  /** One card's data: what a Livery slot binds, and what its Compose UI twin reads. */
  private sealed class Card {
    abstract val query: TemplateQuery
    abstract val data: JsonObject

    class Banner(val index: Int) : Card() {
      val headline = "Try Premium free #$index"
      val body = "Offline downloads and no ads for 30 days, for card $index."
      val cta = "Start trial"
      override val query = TemplateQuery("promo/banner@1", Capabilities, designId = "banner-classic")
      override val data = JsonObject(mapOf("headline" to JsonPrimitive(headline), "body" to JsonPrimitive(body), "cta" to JsonPrimitive(cta)))
    }

    class Poll(val index: Int) : Card() {
      val question = "Which language do you write your UI in? (#$index)"
      val summary = "${100 + index} votes"
      val options = listOf(Option("Kotlin", 0.52f, "52%"), Option("Swift", 0.27f, "27%"), Option("TypeScript", 0.21f, "21%"))
      override val query = TemplateQuery("social/poll@1", Capabilities, designId = "poll-card")
      override val data = JsonObject(
        mapOf(
          "question" to JsonPrimitive(question),
          "summary" to JsonPrimitive(summary),
          "options" to JsonArray(options.map { JsonObject(mapOf("label" to JsonPrimitive(it.label), "share" to JsonPrimitive(it.share), "result" to JsonPrimitive(it.result))) }),
        ),
      )
    }
  }

  private class Option(val label: String, val share: Float, val result: String)

  /** `banner-classic` (livery-capture-cmp's SampleDesigns.kt `BannerClassic`) in Compose UI. */
  @Composable
  private fun BannerCard(card: Card.Banner, modifier: Modifier) {
    Column(
      modifier.clip(RoundedCornerShape(16.dp)).background(Surface).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        BasicText(card.headline, style = TextStyle(OnSurface, 20.sp, FontWeight.Bold))
        BasicText("✕", Modifier.clickable {}.padding(4.dp), style = TextStyle(OnSurface, 18.sp))
      }
      BasicText(card.body, style = TextStyle(OnSurface, 14.sp))
      Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Primary).clickable {}.padding(horizontal = 20.dp, vertical = 10.dp)) {
        BasicText(card.cta, style = TextStyle(Color.White, 14.sp, FontWeight.Medium))
      }
    }
  }

  /** `poll-card` (SampleDesigns.kt `PollCard`) in Compose UI, for the options the data has. */
  @Composable
  private fun PollCard(card: Card.Poll, modifier: Modifier) {
    Column(
      modifier.clip(RoundedCornerShape(16.dp)).background(Surface).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      BasicText(card.question, style = TextStyle(OnSurface, 18.sp, FontWeight.Bold))
      for (option in card.options) {
        Column(
          Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {}.padding(vertical = 4.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            BasicText(option.label, style = TextStyle(OnSurface, 15.sp))
            BasicText(option.result, style = TextStyle(OnSurface, 14.sp))
          }
          Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Track)) {
            Box(Modifier.fillMaxWidth(option.share).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Primary))
          }
        }
      }
      BasicText(card.summary, style = TextStyle(OnSurface, 13.sp))
    }
  }

  companion object {
    const val MODE = "mode"
    const val COMPOSE = "compose"
    const val ITEMS = 200
    private val Capabilities = PlayerCapabilities(Surfaces.IN_APP, maxRcApiLevel = 7, profiles = setOf("androidx"))
    private val Surface = Color(0xFFF7F2FA)
    private val OnSurface = Color(0xFF1D1B20)
    private val Primary = Color(0xFF6750A4)
    private val Track = Color(0x1F79747E)
  }
}
