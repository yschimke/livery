package ee.schimke.livery.compose

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import ee.schimke.livery.client.PlanResult
import kotlinx.coroutines.launch

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
 * thread. Return null from [plan] for an item that is not loaded yet (a paging placeholder): it is
 * asked again when the window next moves or [count] changes.
 *
 * The slot for item `i` must use the same [slotId] and key `key(i)`. Planning and preparing run off
 * the main thread, one request per item; a window of 6 to 10 is enough to stay ahead of a fast fling,
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
  plan: suspend (index: Int) -> PlanResult?,
) {
  val currentPlan = rememberUpdatedState(plan)
  val currentKey = rememberUpdatedState(key)
  LaunchedEffect(host, slotId, ahead, behind) {
    val requested = HashSet<Any?>()
    snapshotFlow { prepareWindow(firstVisible(), lastVisible(), count(), ahead, behind) }
      .collect { window ->
        for (index in window) {
          // No key: the item is not loaded yet (a paging placeholder); asked again when the window next moves.
          val itemKey = currentKey.value(index) ?: continue
          if (!requested.add(itemKey)) continue
          launch {
            val result = currentPlan.value(index)
            if (result == null) requested.remove(itemKey) else host.resources.prepareSlot(slotId, itemKey) { result }
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
