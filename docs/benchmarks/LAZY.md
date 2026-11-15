# Lazy feeds: preparing ahead of the scroll

Pixel 9, Android 17, release build with its baseline profile, 14 November 2026; markdown only, not on the public site. A
feed that does not prepare every card up front plans each one as its slot composes, and
[`LiveryPrepareAhead`](../../livery-compose/src/commonMain/kotlin/ee/schimke/livery/compose/LiveryPrepareAhead.kt) keeps the
cards just ahead of the scroll position planned and prepared (`ahead` of them after the last visible one). Planning is made
to take 20 ms a card here, as an app's own work (a database read, a network call) would; the earlier feed lanes prepared all
200 cards before showing the list and planned in no time.

Lanes (`FeedScrollBenchmark`, one run of 5 iterations, six flings each):

| Lane | What |
| --- | --- |
| `liveryLazyNoAhead` | nothing prepared up front, slots only (the app prepares nothing ahead) |
| `liveryLazyAhead` | `LiveryPrepareAhead(ahead = 8)` |
| `liveryLazyAheadCacheWindow` | the same, with the list's cache window (one viewport ahead, half behind) |
| `pagingNoAhead` / `pagingAhead` | `PagingActivity`: an endless androidx Paging feed (pages of 20 taking 300 ms), slots only / with `ahead = 8` |

## Frame CPU (p50 / p90 / p99, ms) and the margin before a missed frame (overrun p99)

| Lane | p50 | p90 | p99 | overrun p99 | frames |
| --- | --- | --- | --- | --- | --- |
| everything prepared up front (`livery`, earlier run) | 3.8 | 5.9 | 8.3 | -6.0 | 665 |
| `liveryLazyNoAhead` | 2.9 | 7.4 | 11.1 | -3.0 | 657 |
| `liveryLazyAhead` | 3.9 | 6.0 | 8.4 | -5.9 | 661 |
| `liveryLazyAheadCacheWindow` | 3.3 | 7.4 | 10.1 | -3.8 | 648 |
| `pagingNoAhead` | 3.1 | 7.5 | 11.5 | -2.4 | 603 |
| `pagingAhead` | 3.5 | 5.6 | 8.1 | -6.0 | 650 |

- **Preparing 8 cards ahead gives back the up-front numbers.** `liveryLazyAhead` is level with preparing all 200 before the
  list shows (p90 6.0 against 5.9, p99 8.4 against 8.3), and `pagingAhead` is at or under it, without the app knowing the feed.
- **Slots alone have a worse tail**: p99 11.1 and 11.5 ms against 8.1 to 8.4, with the margin before a missed frame
  down from about 6 ms to 2.4 to 3.0. Their median is lower, which is not a win: a card that arrives before its plan is a
  placeholder and is cheap to draw, so the median counts fewer real cards. The frame timing does not count how many cards
  drew late; that needs its own counter (planned).
- **The cache window did not help on top of it** in this run: p90 7.4 and p99 10.1 against 6.0 and 8.4 for `ahead` alone.
  One run, so read it as "no better".
- No lane missed a frame (overrun stays negative).

## How big a window, and how slow a plan (placeholders counted)

`host:placeholder` slices in the trace count cards composed as placeholders, because they reached the screen before they were
ready. One run of 5 iterations, six flings each; frame CPU p99 in ms:

| Lane | planning | placeholders | p99 |
| --- | --- | --- | --- |
| everything prepared up front (`livery`) | none | 0 | 8.4 |
| slots only (`liveryLazyNoAhead`) | 20 ms | 106 | 10.5 |
| slots only (`liveryLazyNoAheadSlow`) | 60 ms | 141 | 12.0 |
| 2 cards ahead | 20 ms | 2 | 8.2 |
| 4 cards ahead | 20 ms | 0 | 8.4 |
| 8 cards ahead (`liveryLazyAhead`) | 20 ms | 0 | 8.1 |
| 16 cards ahead | 20 ms | 0 | 8.0 |
| 8 cards ahead (`liveryLazyAheadSlow`) | 60 ms | 0 | 8.7 |
| paging, slots only | 20 ms | 95 | 10.5 |
| paging, 8 ahead | 20 ms | 0 | 8.6 |

A window of 4 removes every placeholder at 20 ms a plan on this phone and feed; 2 leaves 2. The window needs to cover the
planning time of a fast fling (about 6 cards a second here), so a slower plan wants a bigger one: 8 still held at 60 ms.

## Animation: the visible polls' numbers change every second while the list flings

`liveryAnimated`, `liveryEmbeddedAnimated` and `composeUiAnimated` (the twin's bars ease to their new shares too, as the
template's do), with prepare-ahead on for Livery.

| Lane | p99 frame CPU | overrun p99 | frames | placeholders |
| --- | --- | --- | --- | --- |
| Compose UI twin (bars animated) | 12.0 | -1.5 | 724 | 0 |
| Livery, before the slot fix | 17.2 | +3.0 | 716 | 166 |
| Livery, with the slot fix | 21.4 | +8.8 | 872 | 1 |
| Livery embedded, before the fix | 23.1 | +9.0 | 754 | 173 |
| Livery embedded, with the fix | 21.7 | +10.4 | 772 | 1 |

- **A flicker.** A `LiverySlot` whose key changed (new data) drew the placeholder while it planned the new answer: 166
  placeholders in the run, card, placeholder, card on every update. Fixed in the slot (it keeps the card it drew while its
  `identity` is the same: the feed passes the card's index), which took them to 1.
- **The cost of an update is what remains.** Main thread, during the fling (iteration 2): `Recomposer:recompose` 901 ms for Livery
  against 107 ms for the twin; 439 composition passes against 151; the template composed 244 times at about 1.4 ms (350 ms
  in all) against the twin's 0.7 ms an update. Binding is not it (`livery:bind` 427 calls, 54 ms in all, and 424 prepares for
  17 ms): it is the player composing a document node by node for every change.
- **Layout animation is part of the extra frames**: the template eases a bar to its new width, as 112 ms of animation-phase work
  (the twin, with its bars animated, spends 6 ms); Livery draws 872 frames to the twin's 724.
- Next: a player-alone animated lane (to split the player's cost from Livery's own), and whether an update that only changes values
  can recompose less than the whole template.

## Running them

Gradle's connected-test task was killed repeatedly part-way through on this machine ("daemon disappeared") from a point I did
not find; the runs here are the same instrumentation started with `adb`, on the APKs Gradle built:

```sh
adb install -r livery-baselineprofile/host/build/outputs/apk/benchmarkRelease/livery-baselineprofile-host-benchmarkRelease.apk
adb install -r livery-baselineprofile/benchmark/build/outputs/apk/benchmarkRelease/livery-benchmark-benchmarkRelease.apk
adb shell am instrument -w -e class ee.schimke.livery.benchmark.FeedScrollBenchmark#liveryLazyAhead \
  -e additionalTestOutputDir /sdcard/Download/out ee.schimke.livery.benchmark/androidx.test.runner.AndroidJUnitRunner
```

## Not measured

- Cards drawn as placeholders (how often a card reached the screen unprepared), which is what `ahead` is for.
- Other window sizes (`ahead` of 2, 4, 16), and a planning cost other than 20 ms.
- A cold start of the paging feed, and a scroll back over cards the plan cache has evicted.
