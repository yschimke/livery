---
name: perfetto-traces
description: Reads and compares the Perfetto traces Livery's Macrobenchmarks record (cold start, fling, data change): finding trace_processor_shell, querying slices with SQL, lining up a launch, reading frames, and comparing the Compose UI and Livery lanes. Use when asked what a benchmark run spent its time on, why one lane is slower, to compare two traces, or to add trace sections or SQL to the benchmark analysis.
---
# Perfetto traces from the Livery benchmarks

`:livery-benchmark` (livery-baselineprofile/benchmark) records one Perfetto trace per iteration. They are
build output and are never committed; the numbers that matter are published in `site/pages/benchmarks.md`.

Where they are, after `./gradlew :livery-benchmark:connectedBenchmarkReleaseAndroidTest`:

```
livery-baselineprofile/benchmark/build/outputs/connected_android_test_additional_output/benchmarkRelease/connected/<device>/
  <Class>_<test>_iter<NNN>_<date>.perfetto-trace        e.g. DocumentBenchmark_liveryStartup_iter002_….perfetto-trace
```

The device folder name has spaces (`Pixel 9 - 17`): quote paths, or use `find … -name`. The connected run
uninstalls the host app when it finishes; `adb install -r` the host APK to look at it afterwards.

## The comparison script

```sh
python3 livery-baselineprofile/traces/compare.py <dir with the traces> [--a composeUi] [--b livery] \
  [--only startup|fling] [--svg site/assets/benchmarks]
```

It takes the median over the iterations of two lanes (`composeUi`, `livery`, `rcPlayer`, `liveryNoDefer`,
`liveryNoFlat`) and prints Markdown tables for cold start and for the fling; `--svg` writes
`startup-timeline.svg` and `fling-per-frame.svg`. Its SQL is in the script; extend that, do not paste
one-off queries into the page.

## trace_processor_shell

Macrobenchmark downloads one to `~/.local/share/perfetto/prebuilts/trace_processor_shell-<hash>`. Otherwise
`curl -LO https://get.perfetto.dev/trace_processor && chmod +x trace_processor`. Set `TRACE_PROCESSOR` to a path
and the script uses it. Run a query file against a trace:

```sh
trace_processor_shell -q query.sql "<trace>"      # -q takes a FILE; CSV on stdout, log lines start with "[" or "column "
```

`trace_processor_shell --help` prints a long usage text, so a bad argument (an unquoted path with a space)
floods the output: check the arguments first.

## Reading these traces (what we learned)

- **The app's process**: `select upid, pid, start_ts from process where name = 'ee.schimke.livery.baselineprofile.host' order by start_ts desc limit 1`.
  The benchmark process and the system are in the same trace.
- **The main thread** is the thread whose `tid = pid` (its name is truncated to `ineprofile.host`). The GPU
  thread is the one named `RenderThread`.
- **Time zero for a launch** is `MetricsLogger:launchObserverNotifyIntentStarted`, the origin
  `StartupTimingMetric` uses. `process.start_ts` is later, so do not use it for start-up times.
- **`timeToFullDisplayMs` ends at the first frame drawn *after* `reportFullyDrawn()`.** A settled screen draws
  nothing after it, so the metric waits for whatever next invalidates the window. That was the emoji2 font
  initializer (~650 ms), until the host removed it and requested a frame after reporting. If a startup number
  looks like a fixed ~half second in every lane, check what drew the frame it ended on.
- **A frame** is a `Choreographer#doFrame` slice on the main thread (depth 0); its draw is a `DrawFrames`
  slice on `RenderThread`. A frame that holds a `draw-VRI[…]` slice actually drew.
- **A lazy list composes and measures upcoming items between frames** (prefetch), outside any `doFrame`.
  Never judge a feed lane by in-frame time alone: read it next to the main thread's whole-window busy time.
- **Compose slices worth knowing**: `Compose:initializeView`, `Recomposer:recompose` / `Compose:recompose`
  (composition), `AndroidOwner:measureAndLayout`, `AndroidOwner:onMeasure`, `TextLayout:initLayout`
  (text layout; a count per frame shows how often text is laid out), `draw-VRI[…]` and `postAndWait` (the draw
  and the sync with the render thread).
- **First GPU use compiles pipelines**: `CreateGraphicsPipeline-CompileAfterCacheMiss` on `RenderThread`,
  tens of milliseconds on a cold start, in every lane. It is the driver, not the template.
- **Background work is invisible unless something traces it.** Coroutines and thread pools show no slices,
  so a gap on the main thread with no slice is idle or untraced background work. Livery's planned
  `livery:plan` / `livery:prepare` / `livery:fonts` sections (docs/benchmarks/PLAN.md) are what would
  attribute it; until they exist, say "untraced", not "idle".
- **Count recompositions**: `Compose:recompose` slices per tap or per frame show a lane doing extra passes
  (the data-change finding: 2 per change in the Remote Compose lanes, 1 in the twin).
- **One iteration is noise.** Every figure the script prints is a median over the iterations; two lanes' "process
  and app start" differ by several ms though the same code runs. Read differences under about 10 ms as noise.

## Adding a section to compare

1. Find it in a trace: `select name, count(*), sum(dur) from slice group by name order by 3 desc limit 40`,
   restricted to the main thread of the app's process.
2. Add the SQL to `compare.py` (`STARTUP_SQL` or `FLING_SQL`: rows of `k`, `v`).
3. Run it on both lanes, keep the claim to what the slices show, and say what is not traced.
4. Refresh the page's tables and SVGs (`--svg`), not the traces.

More worked queries: [references/queries.md](references/queries.md).
