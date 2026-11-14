# Benchmark plan: full-screen documents and feeds

Goal: show in numbers where a Remote Compose template costs more or less than the same screen in
Compose UI, and catch regressions in Livery and rc-players. Every scenario compares lanes drawing
identical pixels: the **Compose UI twin**, the **player alone** (`rc`), **Livery with defaults**
(`livery`), and Livery with one optimisation off per lane.

## Constraints

- **Emulator timings are not usable.** CI's emulator renders in software; every lane measured about
  80 ms CPU a frame. Use it for *counts* (frames, invalidations, nodes, misses). Timings need a
  real device (`./gradlew :livery-benchmark:connectedBenchmarkReleaseAndroidTest`; keep results in
  `docs/benchmarks/`).
- **rc-players 3.3.0 is in** (#71): `LiveryHost.deferLayoutAnimations` and `flattenLayout` default to
  true, so a lane per optimisation is a host argument: `livery-nodefer` (flat layout needs the
  deferral, so it goes too), `livery-noflat` and, for documents, `livery-incomposition`. A
  `preprocessor = null` lane still needs a no-op `RcPreprocessor` handed to the cache.
- Every new scenario needs a Compose UI twin and a `TwinRenderParityTest` case. The document
  scenario puts every lane in the same frame (padded, top-aligned, as wide as the screen; the sample
  template takes its own height, so it is a card on a full screen, not a design that fills the
  height) and `thePollTwinMatchesInTheFullScreenFrame` holds the twin to the template there. A
  design that really fills the screen needs its own twin.
- Start-up is measured to a first frame with the document in it. The Livery lanes are a `LiverySlot`,
  as an app writes one. `livery` (and its `nodefer` and `noflat` variants) prepares the first screen with
  Livery's `startup` in `onCreate` and keeps the splash screen up until it is ready, as getting-started
  has it; `livery-nostartup` and `livery-incomposition` have no `startup`, and Livery's first-frame budget
  holds their first frame for the slot's answer. The activity calls `reportFullyDrawn()` on Livery's `Impression` event
  (which comes after the template's first frame, so it adds no frame of its own; the twin and the
  player report after the frame that composed the document). So `timeToInitialDisplayMs` is the first
  frame Livery draws, and `timeToFullDisplayMs` the frame after its impression.

## Status

| Scenario | State |
| --- | --- |
| Feed fling (`FeedScrollBenchmark`, JVM `liveryFeedProfile`) | `livery`, `livery-nodefer`, `livery-noflat`, `rc`, `compose` on device; Livery and Compose UI each with a cache window (one viewport ahead, half behind) and with pausable composition in prefetch; the JVM profile has its own lanes |
| Document: cold start to first frame | `DocumentBenchmark.*Startup` (livery, livery-nostartup, livery-nodefer, livery-noflat, livery-incomposition, rc, compose) |
| Document: data change | `DocumentBenchmark.*Change` (`FrameTimingMetric` over 10 taps); first change not yet separated |
| Document: warm re-open, continuous animation, resize / theme | Planned |
| Document: bundled vs downloaded, with / without baseline profile | Planned (`CompilationMode.None` vs `Partial`) |
| Feed: first draw, warm vs cold templates, layout shift | Planned |
| Feed: scroll back (`resources.misses == 0`), data updates, catalog update mid-scroll | Planned |
| Idle frames, semantics nodes, layout nodes, recompositions (counts, CI gates) | Planned |
| Memory, background throughput microbenchmark (`livery-microbenchmark`), interaction latency | Planned |
| Large canvas-heavy documents | Planned |

## Livery and rc-player metrics

Frame time says a lane is slower; these say why. They are counts, so they are stable on the CI
emulator and can gate a PR. Each benchmark reads them once its scenario ends.

| Metric | Source | What it tells you |
| --- | --- | --- |
| Documents prepared (parsed, linked, rewritten for the display) | `RcPreparedDocumentCache.prepared` (`host.resources.documents`) | How many times a template was decoded. A feed of 200 cards of 2 templates should be 2, not 200 |
| Documents reused | cards drawn minus `prepared`, or a hit counter beside `misses` (to add) | Whether cards share a prepared document, and scroll-back re-prepares nothing |
| Cache misses | `RcPreparedDocumentCache.misses` | Lookups that found nothing prepared |
| First-draw misses | `LiveryResources.misses` | Cards composed before their document was ready: should be 0 when preloaded, 1 per template when not |
| Cache size against `maxTemplates` | `RcPreparedDocumentCache.size` | Eviction pressure and memory |
| Slot plans kept and reused | `LiveryResources.lastPlan` hits (counter to add) | Scroll-back draws without replanning |
| Placeholder-height hits | `LiveryResources.lastHeight` (counter to add) | Layout shift when cards arrive |
| Frames requested while idle, layout nodes, recompositions | `FrameMetrics`, node counts, recomposition tracing | Static screens cost nothing |

The cache counters exist in rc-players 3.2.0 (`prepared`, `misses`, `size`); the ones marked *to add*
are small counters next to `LiveryResources.misses`.

Getting them out of the app: the host logs a one-line summary when a scenario ends
(`livery-metrics prepared=2 misses=0 size=2 ...`), and the benchmark reads it from logcat and writes
it beside the Macrobenchmark JSON, so `benchmark-summary.py` can put it in the same table.

### Tracing background work

*Built.* Preparation, planning and fonts run off the main thread, so frame metrics never see them. `LiveryTrace`
(`livery-client`) writes `livery:<phase>[:<design>]` sections to the system trace on Android, synchronous ones on their
thread and async ones for phases that resume elsewhere. They are listed on the Benchmarks page and read by
`livery-baselineprofile/traces/compare.py`, which totals them per phase in the cold-start window. A first look at where
Livery's cold start goes (median of 5, Pixel 9): ten plans 3.5 ms, document preparation 15.6 ms of which the
`for-display` rewrite 9.1 ms, the card's composition 2.6 ms.

Limits to remember:

- rc-players 3.3.0's published player has **no trace spans** (no `RcTrace` references in its compose and runtime
  artifacts), and on Android its tracer is a stub until an app installs a driver. Inside `prepare` only Livery's own
  `for-display` is visible; the rest is decode and link, as a remainder. Spans in rc-players would split it.
- Macrobenchmark's `TraceSectionMetric` reads thread tracks, so it does not see the async sections. `compare.py` does.
- A section marks a phase, not a count of work: read counts too (ten plans, one prepare in the start-up window).

### Split by template

An average over a mixed feed hides which template is slow, so every metric above is also recorded
per template, keyed by its design id (`poll-card`, `banner-classic`, a real catalog's
`session-live`):

- **Trace sections carry the design id in their name**: `livery:prepare:poll-card`,
  `livery:compose-card:banner-classic`, `livery:fonts:poll-card`. A Perfetto trace then shows which
  template each slice belongs to, and overlapping slices of different templates are visible at a
  glance. Names stay under the 127-character limit; the design id comes from `TemplateEntry`, which is
  already on the plan, so it costs no lookup. Sections are cheap when nothing is recording.
- **`TraceSectionMetric` per template**: the benchmark lists the design ids its host shows and
  registers one metric per `livery:<phase>:<design>` (or one wildcard metric, if the Macrobenchmark
  version in use supports `%` patterns: check at implementation). Each reports count and duration, so
  the results carry a row per template and phase.
- **Counters are keyed the same way**: the host's metrics line is
  `livery-metrics poll-card prepared=1 misses=0 cards=100 ...`, one line per template, so
  `benchmark-summary.py` can print a per-template table beside the totals.
- **Feed lanes vary the template mix**: one template repeated, then each template alone, then mixed.
  A template that is slow alone is slow in the mix; one that is only slow in the mix is a cache or
  scheduling problem.
- **Rc-players' own phases** (`rcTrace`, 3.3.0) are not tagged with a template. Nest them inside
  Livery's per-template section so the trace still attributes them.

## Findings that change the plan

- **Twin parity is checked at density 2.0 only.** On the Pixel 9 (2.625) the twin's card is a little
  taller than the template's (about 1–2 dp). Add a parity case at 2.625 (and 3.0) before treating the
  lanes as the same pixels; if the twin is the one that is off, fix the twin.
- **The Remote Compose lanes recompose twice per data change**: 10 recompositions for 10 changes in the
  twin, 20 in the player alone, 21 in Livery, the same in all five iterations. The player alone has none
  of `LiveryTemplate` in its path, so the second pass is in the player (or in how the host hands it new
  values), not in Livery's `SideEffect`, which this note used to suspect. Find out why in rc-players:
  trace what invalidates the second pass.
- **The first run and the second differ by a few tenths of a millisecond in the fling, and the order of
  Livery and the twin's main-thread time flipped.** Read differences under about 0.5 ms as noise.
- **A slot re-planned every card that scrolled in, and composed it twice.** The `livery:*` sections showed 105 plans in a
  fling, each re-reading the template blob from the APK (0.8 ms) and then replacing the plan the slot already held. The
  new plan never equalled the old one because `LoadedTemplate` held a `ByteArray` (a data class compares an array by
  identity), so every replan recomposed the card: 210 compositions for 105 cards. Fixed by keeping a template's bytes in
  memory by hash and by comparing templates by what names them: per card, planning 1.94 to 0.20 ms, one composition
  instead of two (2.86 to 2.29 ms), no APK reads. At frame level it is small (the recomposer's time per frame fell 40%,
  the frame's p90 3.8 to 3.3 ms; the median did not move).
- **Start-up needs a frame after `reportFullyDrawn()`** and the emoji2 initializer out of the host (see
  the manifest), or the metric measures the next unrelated frame (~690 ms in every lane).
- **The document host plans ten variants before its first draw**, in the Livery and player lanes only:
  a few tenths of the start-up gap may be that. Plan one variant first and the rest after the first
  draw if it matters.

## Cold start: what is being tried, and what is not yet

The traces put Livery's worker at 140 to 159 ms after the launch intent, after the activity's first composition,
while the main thread waited from 131 ms. The worker's ~19 ms is planning (3.5 ms for ten plans) and preparing
the document (15.7 ms, of which the `for-display` rewrite is 9.1 ms).

Done, to measure next:

- **Prepare from `onCreate`.** The `livery` lanes use Livery's `startup` as getting-started has it, and
  `startup` now runs its block on a worker from the moment it is called. Before this, from an activity's
  `lifecycleScope`, its first steps ran on the main thread inside `onCreate` and the rest waited for the main
  thread to come free after the activity was created. The activity's creation (about 97 to 125 ms) should now
  cover the worker's ~19 ms, before the first frame at about 131 ms. `livery-nostartup` keeps the old path, so
  one run shows the difference. Whether the work fits is reported, not inferred: the startup lanes add
  `TraceSectionMetric`s for `livery:startup` (how long it took) and `host:first-frame-held` (how long the
  splash screen held the first frame for it, from the activity's first attempt to draw: near 0 when it fits),
  and `benchmark-summary.py` prints them beside initial and full display. The emulator's numbers are rough;
  the answer that counts is a device's.
- **The bundled manifest is decoded on first use** (`AndroidBundled`), on the thread that first resolves a
  template, instead of on the main thread when the repository is built. And the twin's lane builds no
  repository, where it used to pay for that decode too (3.8 ms), so its numbers were slightly worse than a
  Compose UI app's.

Not yet, and why:

- **Starting at `Application.onCreate`.** Only if the run above still shows the worker on the first frame's
  path. It needs app-level state, which getting-started does not ask an app for.
- **Persisting the display rewrite** (keyed by the source sha, density, font scale and rc-players' version). On
  Android a `startup` already knows the display (`LiveryResources.display` comes from the system's metrics), so
  the rewrite (9.1 ms) stays in its work, on the worker. It matters to the first frame only if that work no
  longer fits in the activity's creation; the run above says. It would still save ~9 ms of worker time per
  template per process. First check whether part of that is code not yet compiled, which a baseline profile of
  the path removes: compare the rewrite of the feed's second template, in a warm process, with the first's.
- **Persisting placeholder heights.** The first screen draws no placeholder (startup or the first-frame
  budget); only cards that arrive later would use them.
- **GPU pipeline compiles** (23 to 27 ms on the render thread) are the same in both lanes, so not Livery's gap.
- **An in-memory copy of each template's bytes** is already in (`DocumentMemo`); the ten `livery:asset-read`
  sections in the traces above predate it.

## Next steps, in order

1. Lanes for the rest of the switches (`preprocessor = null`, preparation in composition for feeds).
2. Add the `livery:<phase>:<designId>` trace sections and the counters above, a metrics line in both host activities, and `TraceSectionMetric` in the benchmarks.
3. Counts as emulator gates, written to the job summary by `benchmark-summary.py`.
4. A device run for timings, results committed here.

## Open questions

- Which device is the timing reference, and where does it run (Firebase Test Lab, a device lab,
  a local phone)?
- Bundle Confetti's real templates (`livery-templates` branch) as a second catalog? They exercise
  live badges, clickable speaker names and visibility, which the samples do not.

Always run lanes interleaved and repeated (single runs vary by ±0.15 ms a card), and include an
empty-box lane so harness cost is subtracted.
