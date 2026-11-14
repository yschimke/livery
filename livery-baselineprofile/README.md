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
| `:livery-benchmark` | A `com.android.test` module whose `FeedScrollBenchmark` flings the host's `FeedActivity`, and whose `DocumentBenchmark` measures start-up and data changes of one full-screen template (`DocumentActivity`), with Macrobenchmark metrics. The feed is the same 200 sample cards (`banner-classic` and `poll-card`), drawn either as Livery templates or as their Compose UI twins. |

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

`FeedActivity` shows one feed of 200 sample cards (`banner-classic`, `poll-card`) in three ways:

| `mode` | Each card is | What it measures |
| --- | --- | --- |
| `livery` | A `LiverySlot`: planned, bound to its contract, tracked for impressions and viewability, playing the template | Livery as an app's feed runs it |
| `rc` | The same prepared, display-rewritten document, played by `RcComposePlayer` with the same values | The Remote Compose player alone |
| `compose` | The same design written in Compose UI (`twins/`: `BannerTwin`, `PollTwin`) | The bar to clear |

The comparison is like for like:

- **The same render.** livery-compose's `TwinRenderParityTest` renders each template and its twin
  with the same data, size and density, and fails if more than 1% of pixels differ beyond
  antialiasing. Today none do. Both renders are kept in `livery-compose/build/twin-parity/`.
- **The same structure.** Each twin has the template's layout, modifiers and text, node for node.
  The one exception: the poll template carries rows for all four of its contract's options and
  hides the unused ones, while the twin composes only the options the data has, as Compose UI
  would be written.
- **Nothing arrives late.** In every mode, what a card needs is in memory before the list shows:
  `livery` prepares each slot with `prepareSlot`, as a feed prepares a page, and `rc` prepares the
  documents for the display.

`FeedScrollBenchmark` runs all three against the `benchmarkRelease` host, which is minified and
installed with the shipped baseline profiles.

`.github/workflows/benchmarks.yml` runs the benchmark on an API 34 emulator for changes to the
libraries. It writes the results, and each lane's ratio to Compose UI, to the job summary
(`.github/scripts/benchmark-summary.py`). An emulator's frame times are a rough comparison between
the lanes, not what a device would show.

Locally, on a device:

```sh
./gradlew :livery-benchmark:connectedBenchmarkReleaseAndroidTest
```

On an emulator, add
`-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`.

## Full-screen document

`DocumentActivity` draws the sample catalog's `poll-card` filling the screen, chosen by the `lane`
extra: `livery`, `livery-incomposition` (prepared on the main thread in the first composition),
`rc` or `compose` (the twin). A "Change" row pushes new data. `DocumentBenchmark` records cold
start to first frame and frame timing across ten changes for each lane. What is built and what is
planned is in [`docs/benchmarks/PLAN.md`](../docs/benchmarks/PLAN.md).
