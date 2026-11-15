package ee.schimke.livery.baselineprofile.host

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
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
import ee.schimke.livery.client.TemplateRepository
import ee.schimke.livery.client.TrustPolicy
import ee.schimke.livery.compose.DEFAULT_PREPARE_AHEAD
import ee.schimke.livery.compose.LiveryHost
import ee.schimke.livery.compose.LiveryPrepareAhead
import ee.schimke.livery.compose.LiveryResources
import ee.schimke.livery.compose.LiverySlot
import kotlinx.coroutines.delay

/**
 * An infinite feed of Livery cards from androidx Paging: [PAGE_SIZE] cards a page, the next page loading
 * as the list nears its end, each page taking `pageDelayMs` (a network call). The app does not know the
 * feed up front, so it does not prepare it up front: every card is a [LiverySlot] that plans as it composes,
 * and [LiveryPrepareAhead] keeps `ahead` cards after the last visible one planned and prepared (so each
 * draws complete when it scrolls in), reading what Paging has loaded with `peek`, which never triggers a
 * load. `ahead` -1 turns the prepare-ahead off, to see the slots alone.
 *
 * Extras: `ahead` (default [DEFAULT_PREPARE_AHEAD]), `pageDelayMs` (default 300), `planDelayMs` (default 0:
 * what planning one card costs the app).
 */
class PagingActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val ahead = intent.getIntExtra(AHEAD, DEFAULT_PREPARE_AHEAD)
    val pageDelayMs = intent.getLongExtra(PAGE_DELAY_MS, 300L)
    val planDelayMs = intent.getLongExtra(PLAN_DELAY_MS, 0L)
    val clock = Clock { System.currentTimeMillis() }
    val repository = TemplateRepository(
      StaticSource("https://catalog.invalid/manifest.json"),
      Fetcher { _, _ -> FetchResult.Failed(503, "offline") },
      JavaCrypto,
      TrustPolicy.AllowUnsigned,
      MemoryBlobStore(),
      MemoryKeyValueStore(),
      clock,
      listOf(FeedActivity.Capabilities),
      bundled = AndroidBundled.fromAssets(this),
    )
    // Room for the window and what Paging keeps loaded, which is more than the list composes.
    val host = LiveryHost(
      repository,
      ActionRouter(emptyMap(), { false }, {}, clock),
      {},
      clock,
      resources = LiveryResources(repository, maxPlans = 512),
      placeholder = { FeedPlaceholder(it) },
    )
    val planner = SlotPlanner(repository, {}, clock)
    suspend fun plan(entry: Entry) = run {
      if (planDelayMs > 0) delay(planDelayMs)
      planner.forAppData(entry.card.query, entry.card.json)
    }
    val pages = Pager(PagingConfig(pageSize = PAGE_SIZE, prefetchDistance = PAGE_SIZE / 2, enablePlaceholders = false)) {
      CardSource(pageDelayMs)
    }.flow.cachedIn(lifecycleScope)

    setContent {
      val items = pages.collectAsLazyPagingItems()
      val listState = rememberLazyListState()
      if (ahead >= 0) {
        // peek: reads what is loaded without asking Paging to load more; null while an item is not there.
        LiveryPrepareAhead(
          state = listState,
          host = host,
          slotId = SLOT,
          count = { items.itemCount },
          ahead = ahead,
          key = { items.peek(it)?.id },
        ) { index -> items.peek(index)?.let { plan(it) } }
      }
      LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding().semantics { testTagsAsResourceId = true; testTag = "feed" },
        state = listState,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(items.itemCount, key = items.itemKey { it.id }) { index ->
          val entry = items[index] ?: return@items
          LiverySlot(key = entry.id, host = host, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), slotId = SLOT) { plan(entry) }
        }
        if (items.loadState.append is LoadState.Loading) {
          item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { BasicText("Loading more…") } }
        }
      }
    }
  }

  private class Entry(val id: Int, val card: FeedActivity.Card)

  /** An endless source: page n is cards n * [PAGE_SIZE] on, alternating banners and polls, after [pageDelayMs]. */
  private class CardSource(private val pageDelayMs: Long) : PagingSource<Int, Entry>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Entry> {
      val page = params.key ?: 0
      delay(pageDelayMs)
      val entries = List(PAGE_SIZE) { i ->
        val id = page * PAGE_SIZE + i
        Entry(id, if (id % 2 == 0) FeedActivity.Card.Banner(id) else FeedActivity.Card.Poll(id))
      }
      return LoadResult.Page(entries, prevKey = null, nextKey = page + 1)
    }

    override fun getRefreshKey(state: PagingState<Int, Entry>): Int? = null
  }

  companion object {
    const val AHEAD = "ahead"
    const val PAGE_DELAY_MS = "pageDelayMs"
    const val PLAN_DELAY_MS = "planDelayMs"
    private const val PAGE_SIZE = 20
    private const val SLOT = "paging"
  }
}
