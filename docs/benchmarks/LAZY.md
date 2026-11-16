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
template's do), with prepare-ahead on for Livery. Pixel 9, spine (5 iterations of 4 flings). Frame CPU p50 / p90 / p99 and the
margin before a missed frame (overrun p99), iteration 2's main thread in the trace:

| Lane | p50 / p90 / p99 | overrun p99 | frames | `Recomposer:recompose` | composition passes | template compositions |
| --- | --- | --- | --- | --- | --- | --- |
| Compose UI twin (bars animated) | 3.4 / 5.7 / 11.9 | -1.7 | 489 | 75 ms (17) | 96 | none |
| Livery, rc-players 3.5.1, before this work | 5.2 / 11.1 / 21.7 | +9.2 | 574 | 901 ms (173) | 439 | 244 at 1.4 ms |
| Livery, slot and request fixes, rc-players 3.5.1 | 5.6 / 9.5 / 17.0 | +2.7 | 564 | 361 ms (88) | 301 | 97 at 0.8 ms |
| Livery, slot and request fixes, rc-players 3.5.3 | 5.4 / 8.8 / 17.0 | +3.0 | 530 | 241 ms (59) | 213 | 96 at 0.9 ms |

(The first row of Livery is the 15 November spine run; the other two are the same lane built with Livery's change, then with
the rc-players fix as well. The twin moved by under 0.3 ms between runs.)

- **A flicker, fixed first.** A `LiverySlot` whose key changed (new data) drew the placeholder while it planned the new answer:
  166 placeholders in the run (counted as composed, so some were off screen), card, placeholder, card on every update. Fixed in
  the slot (it keeps the card it drew while its `identity` is the same: the feed passes the card's index), which took the
  visible ones to 0.
- **Livery's own change did most of the work on the phone**: the slot provided a static composition local with a new lambda for
  every key (so every update recomposed the whole player tree under it), and `LiveryTemplate` built a new request for every plan
  (so the player never skipped a data-only change). p99 21.7 to 17.0 ms, recomposition 901 to 361 ms, template compositions 244
  to 97.
- **The rc-players fix takes a third more off the main thread** (361 to 241 ms of recomposition, 301 to 213 passes) and p90
  from 9.5 to 8.8 ms, but the p99 does not move on the device (17.0 ms). On the JVM, where the same lane runs software-rendered,
  it was far larger (34,400 composable bodies per update down to 2,660): the bodies it removes are mostly cheap on a phone.
  What it fixes: a rounded clip's size readback invalidated the whole player as a bar's width moved (every frame of the easing),
  and a deferred lookahead pass was rebuilt twice for every host change.
- **What is left**: one composition of each updated card per change, and layout: in the last run `AndroidOwner:measureAndLayout`
  took 71 ms in the animation phase over 7 passes, the longest 17 ms, where the twin has none. That is the p99.
- Next: why a bar's layout animation needs a long pass (the lookahead), whether an update that only changes values can skip
  the card's composition (a per-value invalidation in rc-players), and `withoutDefaultAnimations()` for a document whose author
  chose no animation.

### Like for like, with rc-players' per-value invalidation (#915, #916), and the deferral

Since the catalog build turns a template's easing off (#102), `liveryAnimated` lands a bar at once while `composeUiAnimated`'s bars
ease: not the same work. The lanes now come in pairs: `composeUiAnimatedNoEase` against `liveryAnimated`, and `composeUiAnimated`
against `liveryAnimatedEased` (the host's second catalog, built with `--default-animations`), plus `liveryAnimatedEasedNoDefer`
(`deferLayoutAnimations = false`). Pixel 9, rc-players main built from source (3.5.4-SNAPSHOT, released as 3.6.0), spine; p50 / p90 / p99, overrun p99:

| Lane | p50 / p90 / p99 | overrun p99 | frames |
| --- | --- | --- | --- |
| Compose UI, no easing | 3.1 / 5.1 / 12.2 | -1.3 | 469 |
| Livery, no easing (default) | 3.2 / 7.1 / 10.3 | -2.5 | 486 |
| Compose UI, bars ease | 3.3 / 5.7 / 12.2 | -1.1 | 479 |
| Livery, bars ease, default deferral | 4.6 / 8.3 / 16.6 | +2.5 | 520 |
| Livery, bars ease, no deferral | 3.7 / 7.1 / 10.5 | -2.4 | 501 |

- **Without easing, Livery's p99 is under the twin's.** Its p90 is higher (7.1 against 5.1): the update costs more, and the long frames
  are gone (the previous run's p99 was 17.0 ms, 21.7 before the slot and request fixes).
- **The long layout passes are the lookahead deferral.** In the eased lane, `AndroidOwner:measureAndLayout` over 4 ms ran 17 times
  (116 ms, the longest 15.7 ms); inside them `TextAnnotatedStringNode:measure` ran 566 times (28 ms), `TextLayout:initLayout` 415, and 13
  compositions ran in the measure pass: about 33 texts re-measured on every frame of a bar's easing, though only a bar's width
  changed. Without deferral: 3 such passes, 15 ms in all, 34 text measures. Without easing: 5 passes, 29 ms. The twin has none.
- **So deferral is the wrong default for a card that updates on a schedule**: p99 16.6 against 10.5 ms. For a static feed it helps a
  little (spine `livery` p50 3.4 ms against 3.9 with no deferral, 3 iterations). The player's note (#910) says a document changed a
  second time keeps the pass; on this lane it still costs. Next: keep the pass after the first change automatically for a card that has
  changed twice (Livery can count), or fix why the pass re-measures texts whose constraints did not change.

### rc-players 3.6.2-SNAPSHOT (main after 3.6.1, with a host value change composing the player once) and Livery main

Pixel 9, 16 November, spine; p50 / p90 / p99, overrun p99:

| Lane | p50 / p90 / p99 | overrun p99 | frames |
| --- | --- | --- | --- |
| Compose UI, no easing | 3.0 / 4.9 / 12.0 | -1.3 | 463 |
| Livery, no easing (deferred: the default in that run) | 2.4 / 5.0 / 18.8 | +6.6 | 448 |
| Compose UI, bars ease | 3.1 / 5.2 / 12.0 | -1.4 | 490 |
| Livery, bars ease, deferred (the default in that run) | 5.8 / 8.4 / 18.9 | +5.1 | 502 |
| Livery, bars ease, no deferral (today's default) | 2.9 / 5.9 / 18.6 | +5.8 | 474 |

- **The ordinary frame improved a lot** (Livery with no easing: p50 3.2 to 2.4, p90 7.1 to 5.0 against the previous rc-players), **and the
  p99 got worse** (10.3 to 18.8): the same ~18.7 ms in all three Livery lanes. In `liveryAnimated`'s iteration 2, 13 frames were over 12 ms
  (the longest 20 ms; the twin has none): each with about 10 ms of `Recomposer:recompose` (43 composition passes in all) and about 6 ms of
  drawing (`draw-VRI`, 79 ms over the 13). A host value change now composes the player once, on the frame it lands, so every visible
  card's update lands on one frame (every second) where the previous build spread it over several.
- **The lookahead deferral** (now off by default) no longer decides the p99 (18.9 with it, 18.6 without) but still shows in the median of the eased lane (5.8
  against 2.9 ms).
- Next: the tick frame: ~10 ms of composition across the visible cards plus ~6 ms of recording their draw. Spreading a batch of updates
  over frames (or not recording the draw of a card whose update changes only a bar) is the candidate; the twin's worst frames stay at 12 ms.

Since this run `deferLayoutAnimations` is off by default: the eased lane's default is the no-deferral row, and the benchmark lanes
are now `livery` (the default) and `livery-defer`.

The AndroidX embedded player's animated lane (not on the public page) went from p99 21.5 to 11.8 ms with Livery's change to
how that adapter takes an update through its request.

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
