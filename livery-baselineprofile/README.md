# Baseline profiles for the Android libraries

`livery-protocol`, `livery-client` and `livery-compose` each ship a baseline profile in their AAR
(`src/androidMain/generated/baselineProfiles/`). An app that shows Livery templates then gets the
hot paths AOT-compiled at install, without profiling them itself.

`livery-compose`'s share also includes the Remote Compose player's code on the paths Livery drives:

- prepared documents;
- state seeds;
- background preparation and the rewrite for the display.

The rc-players AARs ship a profile of their own corpus, and an app's build merges both.

| Module | Role |
| --- | --- |
| `:livery-baselineprofile-host` | An app that bundles the sample catalog (built by `livery build`) and shows every design, with every example of its contract, as `LiverySlot`s in one scrolling list. The list repeats each design several times, like a feed. |
| `:livery-baselineprofile` | A `com.android.test` module whose `CatalogBaselineProfileGenerator` flings that list end to end. |
| `:livery-benchmark` | A `com.android.test` module whose `FeedScrollBenchmark` flings the host's `FeedActivity` with Macrobenchmark's `FrameTimingMetric`. The feed is the same 200 sample cards (`banner-classic` and `poll-card`), drawn either as Livery templates or as their Compose UI twins. |

One collection feeds all three libraries. Each library applies `androidx.baselineprofile` with a
`filter` on its own packages. Normal builds only package the checked-in file
(`automaticGenerationDuringBuild = false`).

## Generating

`.github/workflows/baseline-profiles.yml` boots an API 34 emulator, runs the generator, and uploads
the three profiles as the `baseline-profiles` artifact. It also prints them as a base64 tarball
between `BASELINE-PROFILES-BEGIN` and `BASELINE-PROFILES-END` in its log. Commit the files over each
library's `src/androidMain/generated/baselineProfiles/`.

Locally, with an emulator or device on API 33+ (or rooted) connected:

```sh
./gradlew :livery-protocol:generateBaselineProfile :livery-client:generateBaselineProfile \
  :livery-compose:generateBaselineProfile
```

The generator runs once, and each task writes its library's filtered share. Add
`-Pbaselineprofile.gmd=true` to let Gradle boot its own emulator (`pixel6Api34`) instead.

## Benchmarking against Compose UI

`FeedActivity` shows one feed in two ways. With `mode=livery`, each card is a `LiverySlot` playing
the sample template. With `mode=compose`, it is the same design written directly in Compose UI
(`BannerCard`, `PollCard`). Both modes use the same data and the same list.

`FeedScrollBenchmark` runs both against the `benchmarkRelease` host, which is minified and installed
with the shipped baseline profiles.

`.github/workflows/benchmarks.yml` runs the benchmark on an API 34 emulator for changes to the
libraries. It writes the two results, and their ratio, to the job summary
(`.github/scripts/benchmark-summary.py`). An emulator's frame times are a rough comparison between
the two modes, not what a device would show.

Locally, on a device:

```sh
./gradlew :livery-benchmark:connectedBenchmarkReleaseAndroidTest
```

On an emulator, add
`-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`.
