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

`host:placeholder` slices count placeholders that became visible: cards that reached the screen before they were ready (a
placeholder Compose prefetch composed off screen and replaced before it scrolled in is not counted). One run, five
iterations of six flings; frame CPU p99 in ms:

| Lane | planning | placeholders | p99 |
| --- | --- | --- | --- |
| everything prepared up front (`livery`) | none | 0 | 8.4 |
| slots only (`liveryLazyNoAhead`) | 20 ms | 18 | 11.0 |
| slots only (`liveryLazyNoAheadSlow`) | 60 ms | 108 | 12.2 |
| 2 cards ahead | 20 ms | 0 | 8.5 |
| 4 cards ahead | 20 ms | 0 | 8.3 |
| 8 cards ahead (`liveryLazyAhead`, the default) | 20 ms | 0 | 8.3 |
| 8 cards ahead (`liveryLazyAheadSlow`) | 60 ms | 0 | 8.1 |
| paging, slots only | 20 ms | 15 | 10.5 |
| paging, 8 ahead | 20 ms | 0 | 8.9 |

Even a window of 2 leaves no visible placeholder at 20 ms a plan; the p99 is the up-front one in every ahead lane. The slots-only
lanes show how much a window buys: 18 visible placeholders and p99 11.0, and with a plan three times slower 108 and 12.2. The
window has to cover the planning time of the fastest fling (about 6 cards a second here), so a slower plan wants a bigger one:
8 held at 60 ms. The default stays 8: the cheapest setting with a margin, and the p99 does not move between 2 and 16.

## Animation: the visible polls' numbers change every second while the list flings

`liveryAnimated`, `liveryEmbeddedAnimated` and `composeUiAnimated` (the twin's bars ease to their new shares too, as the
template's do), with prepare-ahead on for Livery.

| Lane | p99 frame CPU | overrun p99 | frames | placeholders |
| --- | --- | --- | --- | --- |
| Compose UI twin (bars animated) | 12.0 | -1.5 | 724 | 0 |
| Livery, before the slot fix | 17.2 | +3.0 | 716 | 166 |
| Livery, with the slot fix | 21.1 | +8.2 | 882 | 0 |
| Livery embedded, before the fix | 23.1 | +9.0 | 754 | 173 |
| Livery embedded, with the fix | 21.7 | +10.4 | 772 | – |

- **A flicker.** A `LiverySlot` whose key changed (new data) drew the placeholder while it planned the new answer: 166
  placeholders in the run (counted as composed, so some were off screen), card, placeholder, card on every update. Fixed in the
  slot (it keeps the card it drew while its `identity` is the same: the feed passes the card's index), which took the visible
  ones to 0.
- **The cost of an update is what remains.** Main thread, during the fling (iteration 2): `Recomposer:recompose` 901 ms for Livery
  against 107 ms for the twin; 439 composition passes against 151; the template composed 244 times at about 1.4 ms (350 ms
  in all) against the twin's 0.7 ms an update. Binding is not it (`livery:bind` 427 calls, 54 ms in all, and 424 prepares for
  17 ms): it is the player composing a document node by node for every change.
- **Layout animation is part of the extra frames**: the template eases a bar to its new width, as 112 ms of animation-phase work
  (the twin, with its bars animated, spends 6 ms); Livery draws 872 frames to the twin's 724.
### Where an update's cost goes (JVM, 15 November 2026)

`./gradlew :livery-compose:liveryAnimatedProfile` plays the same lane on the JVM (Compose Desktop, software raster, so the shape
of the numbers rather than their size): poll cards in a `LazyColumn`, every card's numbers changing once a second, drawn by the
twin, by the player alone with its values map updated in place (`rc`), and by Livery. Besides frame times it counts every
composable body that ran, with Compose's composition tracer. The list is held still (`-Plivery.feed.step=0`) to show the
updates alone; scrolling 4 px a frame gives the same picture. With no updates all three cost the same (p99 5.1 to 6.2 ms).

| Lane | p90 | p99 | bodies run per update |
| --- | ---: | ---: | ---: |
| Compose UI twin, bars eased | 4.82 ms | 6.55 ms | 768 |
| Compose UI twin, no easing | 4.80 ms | 6.30 ms | 169 |
| player alone, rc-players 3.5.1 | 9.92 ms | 18.15 ms | 34,398 |
| Livery, rc-players 3.5.1 | 9.05 ms | 16.85 ms | 35,911 |
| Livery with its fixes, rc-players 3.5.1 | 9.96 ms | 16.07 ms | 34,564 |
| player alone, yschimke/rc-players#910 | 4.74 ms | 14.51 ms | 2,660 |
| Livery with its fixes, #910 | 4.84 ms | 12.98 ms | 2,826 |
| Livery, #910, templates through `withoutDefaultAnimations()` | 4.44 ms | 11.64 ms | 1,274 |

- **The player alone costs what Livery costs**: Livery adds about 1,500 bodies to the player's 34,400. Almost all of it was in
  rc-players. Bodies by frame after an update, player alone: `24 2185 1922 1922 … (17 frames) 213 213 … 878`: the whole card
  composed again on every frame of the bar's 300 ms easing. The twin: `196 0 158 158 158 17 17 17`.
- **Why, in rc-players** ([yschimke/rc-players#910](https://github.com/yschimke/rc-players/pull/910)): the creation library writes a
  size readback for every rounded clip, and a component publishing its size from the measure pass invalidated the whole player
  whenever the size moved, so every frame of a bar's easing recomposed every node. Also, a player that defers the lookahead pass
  rebuilt the tree twice for each change, switching the pass on as the change landed and off once it settled. #910 repaints
  instead when nothing laid out reads the size, and keeps the pass for a document changed a second time.
- **Livery's own share**: a slot handed a new `LocalLiveryCannotPlay` lambda (a static local) on every new key, which recomposed
  the whole player tree under it; and `LiveryTemplate` built a new `PlayerRequest` for every plan, so the player never skipped a
  data-only change. Both fixed. That is the 34,564 against 35,911 above, and the 166 bodies a Livery update costs over the
  player alone with #910 (64 + 111 + 1,989 + 427 against 24 + 1,989 + 427).
- **What remains**: a changed value still composes the whole card once (about 480 bodies a card), because one invalidation
  counter drives every node in the player. That, all on one frame when every visible card updates at once, is the p99 gap to
  the twin. The easing itself is now cheap (p90 matches the twin's).
- **Animation as an author's choice**: rc-players' `withoutDefaultAnimations()` gives every component without an animation spec a
  disabled one, so only what the template asks to animate does, and a template with nothing to animate never builds the
  lookahead pass. Livery can apply it at catalog build time or as a client option (not yet: it needs the rc-players release).
- Not yet measured on the Pixel 9: the lanes above with #910, and the spine before and after.

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
