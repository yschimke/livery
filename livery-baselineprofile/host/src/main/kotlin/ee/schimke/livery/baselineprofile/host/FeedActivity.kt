package ee.schimke.livery.baselineprofile.host

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import ee.schimke.composeai.rcplayer.compose.rememberRcPreparedDocument
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.livery.client.ActionRouter
import ee.schimke.livery.client.AndroidBundled
import ee.schimke.livery.client.BindResult
import ee.schimke.livery.client.Clock
import ee.schimke.livery.client.ContractBinder
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
import ee.schimke.livery.androidx.LiveryAndroidxPlayers
import ee.schimke.livery.compose.LiveryHost
import ee.schimke.livery.compose.DEFAULT_PREPARE_AHEAD
import ee.schimke.livery.compose.LiveryPlayer
import ee.schimke.livery.compose.LiveryPrepareAhead
import ee.schimke.livery.compose.LiveryResources
import ee.schimke.livery.compose.LiverySlot
import ee.schimke.livery.compose.rememberLiveryDisplay
import ee.schimke.livery.compose.toPlayerValues
import ee.schimke.livery.protocol.PlayerCapabilities
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.twins.BannerData
import ee.schimke.livery.twins.BannerTwin
import ee.schimke.livery.twins.PollData
import ee.schimke.livery.twins.PollOption
import ee.schimke.livery.twins.PollTwin
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The feed `:livery-benchmark` flings: [ITEMS] cards, alternating the sample catalog's
 * `banner-classic` and `poll-card`, each with its own data, drawn one of three ways (`mode`):
 * - `livery`: a [LiverySlot] per card, as an app's feed is built: planned, bound to the contract,
 *   tracked for impressions and viewability, playing the template; `livery-noflat` is the same with `flattenLayout = false`,
 *   so each optimisation's share stays measured;
 * - `rc`: the same prepared documents played directly by [RcComposePlayer] with the same values,
 *   with nothing of Livery around them: the player alone;
 * - `compose`: the twins (livery-baselineprofile/twins), the same designs written in Compose UI,
 *   which livery-compose's TwinRenderParityTest holds to the templates' pixels.
 *
 * Every mode has everything a card needs in memory before the list shows (Livery: each slot
 * prepared with `prepareSlot`, as a feed prepares a page; rc: the documents prepared for this
 * display), so in no mode does a card arrive empty and catch up a frame later.
 *
 * Two of Compose's own prefetch options, for any mode, so each is measured for Livery and for the
 * twin alike. Neither is Livery's: the cache window is the app's list's, and the flag is Compose's.
 * - [CACHE_AHEAD] / [CACHE_BEHIND] (floats, fractions of the viewport): the list's
 *   [LazyLayoutCacheWindow]. Cards ahead of the viewport are composed and measured in prefetch,
 *   between frames, before they scroll in; cards behind it stay composed for a scroll back. Absent,
 *   the list's default (one item ahead).
 * - [PAUSABLE] (boolean): `ComposeFoundationFlags.isPausableCompositionInPrefetchEnabled`, which
 *   lets prefetch compose an upcoming card across several frames' idle time instead of in one go.
 *   Compose turned it off by default in 1.10.6 for stability; this measures it, it doesn't
 *   recommend it.
 */
class FeedActivity : ComponentActivity() {
  @OptIn(ExperimentalFoundationApi::class)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val mode = intent.getStringExtra(MODE) ?: LIVERY
    // A process-wide flag: set on every launch, the library's own default when this one doesn't ask,
    // since a warm benchmark run keeps the process (and the last lane's setting) alive.
    ComposeFoundationFlags.isPausableCompositionInPrefetchEnabled =
      intent.getBooleanExtra(PAUSABLE, DefaultPausable)
    val cacheWindow = intent.takeIf { it.hasExtra(CACHE_AHEAD) || it.hasExtra(CACHE_BEHIND) }?.let {
      LazyLayoutCacheWindow(
        aheadFraction = it.getFloatExtra(CACHE_AHEAD, 0f),
        behindFraction = it.getFloatExtra(CACHE_BEHIND, 0f),
      )
    }
    val cards = List(ITEMS) { i -> if (i % 2 == 0) Card.Banner(i) else Card.Poll(i) }
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
      // `eased`: the catalog built with AndroidX's default animations kept, so a bar's template eases as the twin's does.
      bundled = AndroidBundled.fromAssets(this, if (intent.getBooleanExtra(EASED, false)) "livery-animated" else "livery"),
    )
    // Room for every card's answer: a slot whose prepared answer was evicted would arrive empty
    // and replan, which the other modes never do.
    val host = LiveryHost(
      repository,
      ActionRouter(emptyMap(), { false }, {}, clock),
      {},
      clock,
      resources = LiveryResources(repository, maxPlans = ITEMS),
      placeholder = { FeedPlaceholder(it) },
      flattenLayout = mode != LIVERY_NO_FLAT,
      player = when (mode) {
        LIVERY_VIEW -> LiveryAndroidxPlayers.View
        LIVERY_EMBEDDED -> LiveryAndroidxPlayers.Embedded
        else -> LiveryPlayer.Cmp
      },
    )
    val planner = SlotPlanner(repository, {}, clock)
    // Lazy: the Livery modes prepare nothing up front. Each slot plans as it composes, and LiveryPrepareAhead keeps
    // [lazyAhead] cards ahead of the scroll position prepared (-1: slots only, no prepare-ahead). planDelayMs stands in
    // for an app's own work to plan an item (a database read, a network call).
    val lazyAhead = if (intent.hasExtra(AHEAD)) intent.getIntExtra(AHEAD, DEFAULT_PREPARE_AHEAD) else null
    val planDelayMs = intent.getLongExtra(PLAN_DELAY_MS, 0L)
    // Animated: every [animateMs] the visible polls get new numbers, as a chart that updates on a schedule would.
    val animateMs = intent.getLongExtra(ANIMATE_MS, 0L)
    suspend fun plan(card: Card): PlanResult {
      if (planDelayMs > 0) delay(planDelayMs)
      return planner.forAppData(card.query, card.json)
    }
    setContent {
      val display = rememberLiveryDisplay()
      var tick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
      LaunchedEffect(animateMs) { if (animateMs > 0) while (true) { delay(animateMs); tick++ } }
      var ready by remember { mutableStateOf(mode == COMPOSE || (lazyAhead != null && mode.startsWith("livery"))) }
      // The rc mode's values per card, bound once, as a Livery slot binds its card's data once.
      val values = remember { mutableStateMapOf<Int, Map<String, RcNamedValue>>() }
      LaunchedEffect(display) {
        when (mode) {
          LIVERY, LIVERY_NO_FLAT, LIVERY_VIEW, LIVERY_EMBEDDED -> coroutineScope {
            host.resources.display = display
            if (lazyAhead == null) cards.forEachIndexed { index, card -> launch { host.resources.prepareSlot(SLOT, index) { plan(card) } } }
          }
          RC -> {
            host.resources.warm(listOf(cards[0].query, cards[1].query), display)
            val manifest = repository.current()
            cards.forEachIndexed { index, card ->
              val contract = manifest?.contract(card.query.contract) ?: return@forEachIndexed
              (ContractBinder.bind(contract, card.json) as? BindResult.Ok)?.let { values[index] = it.bindings.toPlayerValues() }
            }
          }
        }
        ready = true
      }
      if (!ready) return@setContent
      val listState = if (cacheWindow != null) rememberLazyListState(cacheWindow) else rememberLazyListState()
      if (lazyAhead != null && lazyAhead >= 0 && mode.startsWith("livery")) {
        // The same card and key a slot below uses at this tick, so what is prepared ahead is what scrolls in.
        LiveryPrepareAhead(listState, host, SLOT, count = { cards.size }, ahead = lazyAhead, key = { slotKey(it, tick) }) { index -> plan(cardAt(cards, index, tick, animateMs)) }
      }
      LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding().semantics { testTagsAsResourceId = true; testTag = "feed" },
        state = listState,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(cards.size, key = { it }, contentType = { cards[it]::class }) { index ->
          val card = cardAt(cards, index, tick, animateMs)
          val version = if (card is Card.Poll) card.version else 0
          val modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
          when (mode) {
            LIVERY, LIVERY_NO_FLAT, LIVERY_VIEW, LIVERY_EMBEDDED -> LiverySlot(key = slotKey(index, if (version > 0) version else 0), host = host, modifier = modifier, slotId = SLOT, identity = index) { plan(card) }
            RC -> PlayerCard(card, values[index].orEmpty(), host, modifier)
            else -> when (card) {
              is Card.Banner -> BannerTwin(card.data, modifier)
              is Card.Poll -> PollTwin(card.data, modifier, animateShares = animateMs > 0 && intent.getBooleanExtra(TWIN_EASES, true))
            }
          }
        }
      }
    }
  }

  /** The template, prepared as Livery prepares it, played with the card's values and nothing else. */
  @Composable
  private fun PlayerCard(card: Card, values: Map<String, RcNamedValue>, host: LiveryHost, modifier: Modifier) {
    val bytes = remember(card.query) { cardBytes[card.query.designId] } ?: return
    val prepared = rememberRcPreparedDocument(bytes, host.preparation) ?: return
    val named = remember(values) { mutableStateMapOf<String, RcNamedValue>().apply { putAll(values) } }
    RcComposePlayer(prepared, modifier, namedValues = named)
  }

  /** Card [index] at animation [tick]: a poll has moved on by then, a banner never changes. */
  private fun cardAt(cards: List<Card>, index: Int, tick: Int, animateMs: Long): Card {
    val base = cards[index]
    return if (animateMs > 0 && tick > 0 && base is Card.Poll) Card.Poll(index, tick) else base
  }

  /** A slot's key for card [index] at [tick]: the index, and the tick for a poll that has been updated. */
  private fun slotKey(index: Int, tick: Int): Any = if (tick > 0 && index % 2 == 1) index to tick else index

  /** The templates' bytes by design, read from the bundled catalog once. */
  private val cardBytes: Map<String?, ByteArray> by lazy {
    val dir = "livery"
    val manifest = assets.open("$dir/manifest.json").use { TemplateRepository.DefaultJson.decodeFromString(ee.schimke.livery.protocol.Manifest.serializer(), it.readBytes().decodeToString()) }
    listOf("banner-classic", "poll-card").associateWith { design ->
      val sha = manifest.templates.first { it.designId == design }.sha256
      assets.open("$dir/${manifest.blobBase}$sha.rc").use { it.readBytes() }
    }
  }

  /** One card's data: what a Livery slot binds, and what its Compose UI twin reads. */
  internal sealed class Card {
    abstract val query: TemplateQuery
    abstract val json: JsonObject

    class Banner(index: Int) : Card() {
      val data = BannerData("Try Premium free #$index", "Offline downloads and no ads for 30 days, for card $index.", "Start trial")
      override val query = TemplateQuery("promo/banner@1", Capabilities, designId = "banner-classic")
      override val json = JsonObject(mapOf("headline" to JsonPrimitive(data.headline), "body" to JsonPrimitive(data.body), "cta" to JsonPrimitive(data.cta)))
    }

    class Poll(index: Int, val version: Int = 0) : Card() {
      // [version] moves the numbers, as an update to a live poll would.
      private val kotlin = if (version == 0) 0.52f else 0.40f + (version % 10) * 0.02f
      val data = PollData(
        "Which language do you write your UI in? (#$index)",
        "${100 + index + version} votes",
        listOf(PollOption("Kotlin", kotlin, "${kotlin.times(100).roundToInt()}%", "✓"), PollOption("Swift", 0.27f, "27%"), PollOption("TypeScript", 0.21f, "21%")),
      )
      override val query = TemplateQuery("social/poll@1", Capabilities, designId = "poll-card")
      override val json = JsonObject(
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
    }
  }

  companion object {
    const val MODE = "mode"
    const val LIVERY = "livery"
    const val LIVERY_NO_FLAT = "livery-noflat"
    const val LIVERY_VIEW = "livery-view"
    const val LIVERY_EMBEDDED = "livery-embedded"
    const val RC = "rc"
    const val COMPOSE = "compose"
    const val ITEMS = 200
    /** Extras: the list's cache window ahead of and behind the viewport, as fractions of it. */
    const val CACHE_AHEAD = "cacheAhead"
    const val CACHE_BEHIND = "cacheBehind"
    /** Extras: lazy preparation (see onCreate): cards kept prepared ahead of the scroll, and a made-up planning delay. */
    const val AHEAD = "ahead"
    const val PLAN_DELAY_MS = "planDelayMs"
    /** Extra: the period, in ms, at which the visible polls' numbers change (0: they do not). */
    const val ANIMATE_MS = "animateMs"
    /** Extra: the templates keep their default animations (the `livery-animated` catalog), so their bars ease. */
    const val EASED = "eased"
    /** Extra (default true): the twin's bars ease to their new shares in the animated lanes; false lands them at once. */
    const val TWIN_EASES = "twinEases"
    /** Extra: pausable composition in prefetch (see the class). */
    const val PAUSABLE = "pausable"
    /** The flag's value as the library ships it, read before any launch sets it. */
    @OptIn(ExperimentalFoundationApi::class)
    private val DefaultPausable = ComposeFoundationFlags.isPausableCompositionInPrefetchEnabled
    private const val SLOT = "feed"
    internal val Capabilities = PlayerCapabilities(Surfaces.IN_APP, maxRcApiLevel = 7, profiles = setOf("androidx"))
  }
}
