# Handover: Livery's cost of updating a card's data

**Task:** find out why updating the data of a visible Livery card costs about twice what the same update costs a Compose UI
card, and make it cheaper without giving up anything Livery guarantees. This note is everything the previous session knew.

**Status (15 November 2026):** split on the JVM, see [`LAZY.md`](LAZY.md#where-an-updates-cost-goes-jvm-15-november-2026).
The player alone costs what Livery does; the cost was mostly rc-players' (hypotheses 3 and 4, plus a rounded clip's size
readback invalidating the whole player on every frame of an easing: yschimke/rc-players#910). Livery's own share was
hypotheses 1 and 6 (a static local given a new lambda per key, and a `PlayerRequest` rebuilt per plan), fixed. Left: the
Pixel 9 lanes and the spine after the rc-players release, one composition per updated card per change, and applying
`withoutDefaultAnimations()` at catalog build time or as a client option.

## Status

Worked on since: Livery's slot and request fixes and rc-players' fix for a rounded clip's size readback and the deferred lookahead
pass are in, and the animated lane was re-run on the phone (`docs/benchmarks/LAZY.md`): p99 21.7 to 17.0 ms against the twin's
11.9, recomposition 901 to 241 ms. Livery's own change gave most of the p99 (17.0 ms on rc-players 3.5.1 too); the rc-players
fix cut recomposition by a third. What remains is one composition of each updated card per change (hypothesis 3 below, a
per-value invalidation in rc-players) and long layout passes in the animation phase (hypothesis 4; up to 17 ms, `measureAndLayout`,
which the twin does not have). The findings below are the starting numbers.

## The finding

In the animated feed lane the visible polls' numbers change every second while the list flings. Pixel 9, Android 17,
rc-players 3.5.1, release build with its baseline profile, 14 to 15 November 2026 (`docs/benchmarks/LAZY.md`):

| | Compose UI twin (bars animated) | Livery | Livery embedded |
| --- | --- | --- | --- |
| frame CPU p50 / p90 / p99 | 3.2 / 5.5 / 12.0 ms | 5.3 / 10.7 / 21.1 ms | 3.3 / 7.7 / 21.5 ms |
| margin before a missed frame, p99 | -1.5 ms (none missed) | +8.2 ms (frames missed) | +11.3 ms |
| frames drawn in the run | 724 | 882 | 772 |
| visible placeholders | 0 | 0 | 0 |

Main thread during the fling, iteration 2 of each (Perfetto; the traces are build output, regenerate them as below):

| | twin | Livery |
| --- | --- | --- |
| `Recomposer:recompose` inside the animation phase | 107 ms (25 slices) | 901 ms (173) |
| `Compose:recompose` passes | 151 | 439 |
| template compositions (`livery:compose:poll-card`) | none | 244, about 1.4 ms each, 350 ms in all |
| the twin's poll update, for comparison | about 0.7 ms | |
| `Recomposer:animation` | 6 ms | 112 ms |
| `AndroidOwner:measureAndLayout` in the animation phase | 2 ms | 118 ms (worst pass 7 to 10 ms) |

What is **not** the cost: binding (`livery:bind`, 427 calls, 54 ms in all), preparing (`livery:prepare`, 424 calls, 17 ms: the
documents are memoized), planning (async, off the main thread), placeholders (0), and the flicker that used to dominate this
lane (fixed in #96: a slot with new data drew the placeholder; 166 per run before).

So the extra time is the player and the template **recomposing, and re-laying-out, on every data change**. Some of the
extra frames are a feature: the template eases a bar to its new width (the twin was given the same easing for a fair
comparison, and spends 6 ms on it). The rest is overhead to find.

## Where to look (hypotheses, most likely first, each with how to test it)

1. **`LiveryTemplate` recomposes in full on every plan.** Its inputs (`RenderPlan`, a data class holding `Bindings`, a map, and
   a `LoadedTemplate`) are not stable to the Compose compiler, so a new plan is never skipped and everything under it
   re-runs: `livery-compose/src/commonMain/kotlin/ee/schimke/livery/compose/LiveryTemplate.kt` (`PlayTemplate`). Test: build with
   Compose compiler metrics (`-P…reportsDestination`) and read what is `unstable`; try `@Immutable`/`@Stable` wrappers, or pass
   the changing part (the values) through state that only the player reads, and keep the rest of `PlayTemplate` from running on
   a data-only change. The trace evidence is 244 `livery:compose` slices, one per update per card, each doing the whole function.
2. **The values map is written wholesale every composition.** `PlayTemplate` computes `(plan.bindings + extra).toPlayerValues()`
   (a fresh map each time, `PlayerValues.kt`), then a `SideEffect` removes absent keys and calls `values.putAll(pushed)` on a
   `SnapshotStateMap`. Check whether `putAll` of equal values invalidates readers (it should not, but verify with the trace: do
   unchanged values still cause `Compose:recompose` passes?). Only write the keys whose value changed.
3. **One state map, coarse invalidation, inside rc-players.** `RcComposePlayer(namedValues = values)` reads the map; if each node
   reads through the map rather than per-name state, one changed value invalidates every reader. 439 passes against the twin's 151
   for one poll update suggests several nodes recompose per change. This may be rc-players' to fix (per-value state in
   `RcPlayerState`); find out by running the player alone (below) and counting passes per changed value.
4. **Layout animation and the lookahead pass.** `LiveryHost.deferLayoutAnimations` skips AndroidX's lookahead until a card's first
   data change, "which still animates, one frame later" (`LiveryTemplate` kdoc). An animated feed pays it on every card's first
   change and on each after. Test: the `noDefer` and `noFlat` lanes on the animated feed (not run; add
   `Options.AnimatedLazy` variants), and `LiveryHost(deferLayoutAnimations = false)` to see the other direction.
   `AndroidOwner:measureAndLayout` at 118 ms against 2 ms is the number to move.
5. **Per-recomposition work around the player** (all in `PlayTemplate`): `VisibilityTracker` (`remember` keyed on bytes, decision,
   item), `onSizeChanged { host.resources.keepHeight(...) }`, `onVisibilityChanged`, `slotModifier(plan)`, the three
   `LaunchedEffect`s, `CompositionLocalProvider`. Each is small; check none re-run on a data-only change (the traces' per-pass
   children will say).
6. **The slot.** A data change replans (`LiverySlot`, key changes), sets `state.value = Ready(plan)`, which recomposes the slot and
   `LiveryTemplate` once for the plan and again for the values `SideEffect` (the second pass is a candidate: two compositions
   per update instead of one). Count passes per update on one card.

## Reproduce, and how to look

Quick iteration without a device: the JVM profile, `./gradlew :livery-compose:liveryFeedProfile` (software rendering; the
shape of the numbers, not their size).

On a device (Pixel 9 was used; a low battery is refused):

```sh
./gradlew :livery-baselineprofile-host:assembleBenchmarkRelease :livery-benchmark:assemble
livery-baselineprofile/run-benchmarks.sh 'FeedScrollBenchmark#liveryAnimated,FeedScrollBenchmark#composeUiAnimated,FeedScrollBenchmark#liveryEmbeddedAnimated' /tmp/animated
```

(`run-benchmarks.sh` runs the instrumentation through `adb`; Gradle's connected task was killed part-way through long runs on the
previous machine. The animated lanes are `FeedActivity` extras `ahead=8`, `planDelayMs=20`, `animateMs=1000`; the twin takes
`animateMs=1000` and animates its bars.) A frame number is in `*-benchmarkData.json` (`frameDurationCpuMs`, `frameOverrunMs`,
and the `host:placeholder` count).

The trace queries that produced the table above (trace_processor_shell, `livery-baselineprofile/traces/`, skill
`perfetto-traces`): the main thread is the thread whose `tid = pid`; take the `animation` slices over 3 ms and total their
descendants by name.

```sql
create perfetto table big as
select s.id, s.dur from slice s join thread_track tt on s.track_id = tt.id
where tt.utid = (select utid from thread where upid = (select upid from process where name = 'ee.schimke.livery.baselineprofile.host' order by start_ts desc limit 1)
                 and tid = (select pid from process where name = 'ee.schimke.livery.baselineprofile.host' order by start_ts desc limit 1))
  and s.name = 'animation' and s.dur > 3e6;
-- run this next, as the last statement of its own invocation:
select d.name, count(*) n, round(sum(d.dur) / 1e6, 1) ms, round(max(d.dur) / 1e6, 2) mx
from big b join descendant_slice(b.id) d
where d.depth - (select depth from slice where id = b.id) <= 3
group by d.name order by 3 desc limit 14;
```

Useful extra measurement to add first: **the player alone with animated data** (`FeedActivity` mode `rc` has no updating values: give
it the same ticker), which splits the player's cost from Livery's own. If the player alone is already most of the gap, the work is in
rc-players (hypothesis 3) and the right move is an issue or PR there with this trace.

## Constraints (from `AGENTS.md`; the first two are why this is not a one-line change)

- **No visible redraws and no player state shared across cards.** A card after the first draws complete on its first frame, and a
  data update must not recreate the player (that was the #96/#97 discussion: `LiverySlot`'s `identity` keeps a card across data
  updates and resets it for another card; `LiveryTemplate` keys its player by decision and feed-item identity). Any change must
  keep `FirstFrameTest` green, including `newDataForASlotKeepsItsCardUntilTheNewAnswerIsReady`,
  `anotherCardIsAFreshPlayerAndStartsFromTheFallback` and `anotherItemGetsAFreshPlayerAndNewDataForTheSameItemKeepsIt`.
- **Rendering never waits on the network; first-frame rules unchanged.** Nothing here may block the main thread.
- **Nothing app-specific in Livery**, and any public API change updates `site/pages/` in the same PR.
- Conventional Commit titles, branches `agent/...`, no agent co-author trailers (`scripts/install-git-hooks.sh`).

## Done means

1. A written split of the 21 ms against 12 ms p99: how much is the player (alone), how much is Livery around it, how much is the
   easing animation the twin now also has. A trace table like the one above for each.
2. A change (or an upstream issue with a reproducer, if it is rc-players') that brings the animated lane's p99 within about 25% of
   the twin's (about 15 ms) with placeholders still 0 and the tests above green; the `Recomposer:recompose` and `livery:compose`
   counts in the animated trace fall in step.
3. No regression in the spine lanes: `livery-baselineprofile/run-benchmarks.sh spine` before and after (the spine is the
   routine's main set, about 25 minutes), with the animated lane's before/after in `docs/benchmarks/LAZY.md`.
4. The public Benchmarks page's animated-feed paragraph updated with the result (it is written plainly against Livery today).

## State of the branches

`agent/livery-feed-animation` (PR #97) has the lanes, the `Tier` structure and `run-benchmarks.sh`; `LiverySlot`'s `identity` and the
keyed player are in `main` (#96) and #97. The persisted-rewrite change (`agent/livery-persisted-rewrite`) is unrelated to this and
is not merged.
