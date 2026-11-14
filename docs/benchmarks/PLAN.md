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
  as an app writes one, with no host code around it: Livery's first-frame budget holds the first frame
  for the slot's answer, and the activity calls `reportFullyDrawn()` on Livery's `Impression` event.
  So `timeToInitialDisplayMs` is the first frame Livery draws, and `timeToFullDisplayMs` the frame
  after its impression.

## Status

| Scenario | State |
| --- | --- |
| Feed fling (`FeedScrollBenchmark`, JVM `liveryFeedProfile`) | `livery`, `livery-nodefer`, `livery-noflat`, `rc`, `compose` on device; the JVM profile has its own lanes |
| Document: cold start to first frame | `DocumentBenchmark.*Startup` (livery, livery-nodefer, livery-noflat, livery-incomposition, rc, compose) |
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

Preparation, planning and fonts run off the main thread, so frame metrics never see them. Wrap each in
a named `android.os.Trace` section (`androidx.tracing.trace`, cheap when no trace is recording) and read
them with `TraceSectionMetric`, which reports count and duration per section:

| Section | Wraps | Answers |
| --- | --- | --- |
| `livery:plan` | `SlotPlanner` / `TemplateSelector` for a slot | How long a card waits for its plan |
| `livery:prepare` | `prepareOrNull` on the background context | Decode, link and `forDisplay` per template; how fast a catalog warms |
| `livery:fonts` | `resources.typefaces` | Time to a fallback-free first frame |
| `livery:images` | image resolution | Network-backed images |
| `livery:compose-card` | one card's composition | Per-card cost in a feed |

Counts matter as much as durations: `livery:prepare` fired 200 times in a 2-template feed is the bug.
rc-players' own `rcTrace` categories (3.3.0) mark the player phases and need no work here. The same
sections show in a Perfetto trace of any run, which is how to see whether preparation overlaps a fling.

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
- **The Remote Compose lanes recompose twice per data change** (20 recompositions for 10 changes, the
  twin 10). Consistent with `LiveryTemplate` pushing the values from a `SideEffect`; unconfirmed.
- **Start-up needs a frame after `reportFullyDrawn()`** and the emoji2 initializer out of the host (see
  the manifest), or the metric measures the next unrelated frame (~690 ms in every lane).
- **The document host plans ten variants before its first draw**, in the Livery and player lanes only:
  a few tenths of the start-up gap may be that. Plan one variant first and the rest after the first
  draw if it matters.

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
