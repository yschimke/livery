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
- **rc-players 3.2.0 is pinned here; the optimisation switches come with 3.3.0.**
  `deferLayoutAnimations` and `flattenLayout` do not exist in 3.2.0, so those lanes wait for the
  bump (Renovate). Lanes available now: `preparation = InComposition` and the default.
  A `preprocessor = null` lane needs a no-op `RcPreprocessor` handed to the cache.
- Every new scenario needs a Compose UI twin and a `TwinRenderParityTest` case. The document
  scenario reuses `PollTwin` at full-screen size; the template and twin are already held to the
  same pixels at card width, a full-screen parity case is still to add.

## Status

| Scenario | State |
| --- | --- |
| Feed fling (`FeedScrollBenchmark`, JVM `liveryFeedProfile`) | Exists: `livery`, `rc`, `compose` on device; plus `rc-defer`, `livery-flat` on the JVM |
| Document: cold start to first frame | `DocumentBenchmark.*Startup` (livery, livery-incomposition, rc, compose) |
| Document: data change | `DocumentBenchmark.*Change` (`FrameTimingMetric` over 10 taps); first change not yet separated |
| Document: warm re-open, continuous animation, resize / theme | Planned |
| Document: bundled vs downloaded, with / without baseline profile | Planned (`CompilationMode.None` vs `Partial`) |
| Feed: first draw, warm vs cold templates, layout shift | Planned |
| Feed: scroll back (`resources.misses == 0`), data updates, catalog update mid-scroll | Planned |
| Idle frames, semantics nodes, layout nodes, recompositions (counts, CI gates) | Planned |
| Memory, background throughput microbenchmark (`livery-microbenchmark`), interaction latency | Planned |
| Large canvas-heavy documents | Planned |

## Next steps, in order

1. Bump rc-players to 3.3.0, then add the `defer-off` and `flat-off` lanes to `DocumentActivity`
   and `FeedActivity` (chosen by the `lane` / `mode` extra, one APK).
2. Add `trace("livery:prepare")` / `trace("livery:plan")` sections and `TraceSectionMetric`.
3. Counts as emulator gates, written to the job summary by `benchmark-summary.py`.
4. A device run for timings, results committed here.

## Open questions

- Which device is the timing reference, and where does it run (Firebase Test Lab, a device lab,
  a local phone)?
- Bundle Confetti's real templates (`livery-templates` branch) as a second catalog? They exercise
  live badges, clickable speaker names and visibility, which the samples do not.

Always run lanes interleaved and repeated (single runs vary by ±0.15 ms a card), and include an
empty-box lane so harness cost is subtracted.
