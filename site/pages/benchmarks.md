title: Benchmarks
order: 31

# Benchmarks

What a template costs against the same screen written in Compose UI, measured on a real phone. Every
lane draws the same pixels: `TwinRenderParityTest` renders each template and its Compose UI twin and
fails if more than 1% of pixels differ, in the feed's card size and in the full-screen frame below.

| Lane | What it is |
| --- | --- |
| Compose UI | The hand-written twin of the design |
| Player alone | The prepared document played by `RcComposePlayer`, nothing of Livery around it |
| Livery | `LiverySlot` / `LiveryTemplate` with the defaults: background preparation, deferred layout animations, flat layout |
| Livery, no deferral | `deferLayoutAnimations = false` (flat layout needs the deferral, so it is off too) |
| Livery, no flat layout | `flattenLayout = false` |

## Results

**Pixel 9, Android 17 (API 37), rc-players 3.3.0, 14 November 2026.** Macrobenchmark on a
`benchmarkRelease` build with the shipped baseline profiles, 5 iterations per test. One device, lanes run
one after another rather than interleaved: differences of a few tenths of a millisecond are inside the
noise, and are shown so you can see that, not as a ranking.

### Feed fling

6 flings down 200 sample cards (`banner-classic` and `poll-card`), `FrameTimingMetric`. A frame's CPU
time, and its overrun against the 16.7 ms budget (negative is time to spare).

| Lane | Frames | CPU p50 | CPU p90 | CPU p99 | Overrun p99 | p50 against Compose UI |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Compose UI | 655 | 3.8 ms | 5.9 ms | 8.0 ms | −6.3 ms | 1.00× |
| Player alone | 669 | 4.1 ms | 6.5 ms | 8.9 ms | −5.3 ms | 1.08× |
| Livery | 663 | 3.4 ms | 6.0 ms | 9.2 ms | −4.8 ms | 0.89× |
| Livery, no deferral | 677 | 4.1 ms | 6.3 ms | 8.4 ms | −5.5 ms | 1.08× |
| Livery, no flat layout | 674 | 4.0 ms | 6.3 ms | 9.3 ms | −4.8 ms | 1.05× |

What this says: on a current phone no lane misses a frame (every overrun is negative), and the lanes
are within about a millisecond of each other. Livery's median is the lowest here, but its 99th
percentile (9.2 ms) is above the Compose UI twin's (8.0 ms). With the optimisations off, Livery's
median moves to the player-alone figure, the direction the deferral and flat layout are meant to move
it. With one run per lane, this does not show that Livery is faster or slower than Compose UI.

### Full-screen document

One template (`poll-card`) in a padded, top-aligned, full-width frame; the sample template takes its
own height, so this is a card on a full screen. Tapping the document pushes new data, ten times.

| Lane | Frames over 10 changes | CPU p50 | CPU p90 | CPU p99 |
| --- | ---: | ---: | ---: | ---: |
| Compose UI | 10 | 4.7 ms | 5.9 ms | 9.5 ms |
| Player alone | 21 | 3.7 ms | 8.1 ms | 9.2 ms |
| Livery | 30 | 3.8 ms | 7.0 ms | 9.5 ms |
| Livery, no deferral | 29 | 3.5 ms | 6.7 ms | 9.1 ms |
| Livery, no flat layout | 30 | 3.4 ms | 7.4 ms | 10.7 ms |

The Remote Compose lanes draw two to three frames per change where the twin draws one, most likely
because the document animates the change and the twin switches at once (not yet confirmed). Per-frame
cost is comparable; the number of frames differs.

### Cold start

Process start to the first frame with the document in it (`timeToFullDisplayMs`).

| Lane | Full display | Initial display |
| --- | ---: | ---: |
| Compose UI | 690 ms | 186 ms |
| Player alone | 692 ms | 148 ms |
| Livery | 695 ms | 151 ms |
| Livery, no deferral | 695 ms | 152 ms |
| Livery, no flat layout | 697 ms | 150 ms |
| Livery, preparing in composition | 680 ms | 147 ms |

**These do not separate the lanes.** Every lane, the Compose UI twin included, takes about 690 ms to
full display, so that time is dominated by something the lanes share, and we have not yet found what.
Do not read a difference into them.

## What is not measured yet

*Planned*: a warm second visit; continuous animation and idle frames; resize and theme change; a
per-template split and counts of documents prepared and reused; background preparation traces; memory;
baseline profile on against off; a low-end device. The plan, in order, is
[`docs/benchmarks/PLAN.md`](https://github.com/yschimke/livery/blob/main/docs/benchmarks/PLAN.md).

## Reproduce

With a phone connected (a device with a low battery is refused, as it throttles):

```sh
./gradlew :livery-benchmark:connectedBenchmarkReleaseAndroidTest
```

The raw medians behind these tables are in
[`docs/benchmarks/results/`](https://github.com/yschimke/livery/tree/main/docs/benchmarks/results).
The host app and benchmarks are in `livery-baselineprofile/`; its README describes each lane. A
JVM profile (`:livery-compose:liveryFeedProfile`) gives the same comparison without a device, with
software rendering, for quick iteration.
