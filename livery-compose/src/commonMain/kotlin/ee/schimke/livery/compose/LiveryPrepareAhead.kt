package ee.schimke.livery.compose

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import ee.schimke.livery.client.PlanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Keeps the cards just ahead of a lazy list's scroll position planned and prepared, so each draws
 * complete on its first frame when it scrolls in, without the app knowing every item up front. An app
 * that has all its data can call [LiveryResources.preload] (or [LiveryResources.prepareSlot]) for the
 * lot instead; one that pages, or fetches as it goes, uses this: [count] is how many items it knows now
 * and grows as pages arrive, and the window follows the list.
 *
 * For each index in the window ([behind] items before the first visible one, [ahead] after the last),
 * not yet requested, it runs [plan] (the call the item's [LiverySlot] makes: `SlotPlanner.forAppData`,
 * `forFeedItem` ...) and keeps the answer for the slot under [slotId] and [key] with
 * [LiveryResources.prepareSlot], which also readies the template's document and fonts off the main
 * thread. Return null from [plan], or a null [key], for an item that is not loaded yet (a paging placeholder): it is
 * asked again when the window moves, [count] changes or its key appears.
 *
 * The slot for item `i` must use the same [slotId] and key `key(i)`. Planning and preparing run off
 * the main thread (on [context], by default [Dispatchers.Default]: [plan] runs there, so it must not read composition
 * state without a snapshot read, which `peek` is), one request per item; a window of 6 to 10 is enough to stay ahead of a fast fling,
 * and [LiveryResources]' `maxPlans` should hold the window plus what the list keeps composed.
 *
 * [firstVisible] and [lastVisible] are read in a snapshot, so they can read list state directly; the
 * [LazyListState] overload does. [key] defaults to the index; give the item's id when items can move, and null for
 * an item not loaded yet.
 */
@Composable
fun LiveryPrepareAhead(
  host: LiveryHost,
  slotId: String,
  firstVisible: () -> Int,
  lastVisible: () -> Int,
  count: () -> Int,
  ahead: Int = DEFAULT_PREPARE_AHEAD,
  behind: Int = 0,
  key: (index: Int) -> Any? = { it },
  context: CoroutineContext = Dispatchers.Default,
  plan: suspend (index: Int) -> PlanResult?,
) {
  val currentPlan = rememberUpdatedState(plan)
  val currentKey = rememberUpdatedState(key)
  LaunchedEffect(host, slotId, ahead, behind, context) {
    val inFlight = HashSet<Any?>()
    // The keys are part of what is observed, so an item that loads (a paging placeholder becoming an item) without the
    // window or the count moving still gets requested.
    snapshotFlow {
      val window = prepareWindow(firstVisible(), lastVisible(), count(), ahead, behind)
      window.map { it to currentKey.value(it) }
    }.collect { window ->
      for ((index, itemKey) in window) {
        // No key: the item is not loaded yet; asked again when its key appears.
        if (itemKey == null || itemKey in inFlight) continue
        // Already planned at this revision and still in memory (bounded: an evicted answer is asked for again).
        if (host.resources.lastPlan(slotId, itemKey, host.repository.revision.value) != null) continue
        inFlight.add(itemKey)
        // Off the main thread: the app's planning and the preparation run on [context], one request per item.
        launch(context) {
          try {
            val result = currentPlan.value(index)
            if (result != null) host.resources.prepareSlot(slotId, itemKey) { result }
          } finally {
            inFlight.remove(itemKey)
          }
        }
      }
    }
  }
}

/** [LiveryPrepareAhead] for a [LazyColumn][androidx.compose.foundation.lazy.LazyColumn] or row, from its [state]. */
@Composable
fun LiveryPrepareAhead(
  state: LazyListState,
  host: LiveryHost,
  slotId: String,
  count: () -> Int,
  ahead: Int = DEFAULT_PREPARE_AHEAD,
  behind: Int = 0,
  key: (index: Int) -> Any? = { it },
  context: CoroutineContext = Dispatchers.Default,
  plan: suspend (index: Int) -> PlanResult?,
) = LiveryPrepareAhead(
  host = host,
  slotId = slotId,
  firstVisible = { state.firstVisibleItemIndex },
  lastVisible = { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: state.firstVisibleItemIndex },
  count = count,
  ahead = ahead,
  behind = behind,
  key = key,
  context = context,
  plan = plan,
)

/** The items to prepare: [behind] before [first] and [ahead] after [last], inside `0 until count`. */
internal fun prepareWindow(first: Int, last: Int, count: Int, ahead: Int, behind: Int): IntRange {
  if (count <= 0) return IntRange.EMPTY
  val from = (first - behind).coerceAtLeast(0)
  val to = (maxOf(first, last) + ahead).coerceAtMost(count - 1)
  return if (from > to) IntRange.EMPTY else from..to
}

/** Enough to stay ahead of a fast fling on a phone, without planning a feed's worth. */
const val DEFAULT_PREPARE_AHEAD: Int = 8
