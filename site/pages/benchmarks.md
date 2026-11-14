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

The two screens, as the benchmark host draws them on the phone: the Compose UI twin on the left, the
Livery template on the right.

<div style="display:flex;gap:1rem;flex-wrap:wrap">
<figure style="margin:0"><img src="assets/benchmarks/document-compose-ui.png" alt="The poll card drawn in Compose UI on the Pixel 9" width="270" height="160"><figcaption>Compose UI twin</figcaption></figure>
<figure style="margin:0"><img src="assets/benchmarks/document-livery.png" alt="The same poll card drawn by Livery on the Pixel 9" width="270" height="160"><figcaption>Livery</figcaption></figure>
</div>

They are not pixel-identical on the phone. The twin's card is a little taller (about 1–2 dp, the rows
sitting slightly lower), although `TwinRenderParityTest` finds no differing pixel at density 2.0 on the JVM.
This phone is at 2.625. Until the test also runs at that density, treat the lanes as the same design, not
the same pixels.

### Feed fling

6 flings down 200 sample cards (`banner-classic` and `poll-card`), `FrameTimingMetric`. A frame's CPU
time, and its overrun against the 16.7 ms budget (negative is time to spare).

| Lane | Frames | CPU p50 | CPU p90 | CPU p99 | Overrun p99 | p50 against Compose UI |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Compose UI | 652 | 3.9 ms | 6.2 ms | 8.4 ms | −5.8 ms | 1.00× |
| Player alone | 673 | 4.1 ms | 6.6 ms | 9.2 ms | −5.1 ms | 1.05× |
| Livery | 663 | 3.4 ms | 5.8 ms | 8.4 ms | −5.8 ms | 0.87× |
| Livery, no deferral | 666 | 4.3 ms | 6.8 ms | 9.2 ms | −4.7 ms | 1.10× |
| Livery, no flat layout | 633 | 3.8 ms | 6.1 ms | 8.7 ms | −5.5 ms | 0.97× |

What this says: on a current phone no lane misses a frame (every overrun is negative), and the lanes sit
within about a millisecond of each other. Livery with its defaults is level with the Compose UI twin, and
turning the deferral off moves it back to the player-alone figure, the direction it is meant to move.
With one run per lane, this does not show that Livery is faster than Compose UI.

### Full-screen document: changing data

One template (`poll-card`) in a padded, top-aligned, full-width frame; the sample template takes its
own height, so this is a card on a full screen. Tapping the document shows the next of ten data variants,
ten times. Every variant is bound to the contract before the screen shows, so the tap itself costs each
lane one swap of state; the cost of binding data is a separate measurement, still *Planned*.

| Lane | Frames over 10 changes | CPU p50 | CPU p90 | CPU p99 |
| --- | ---: | ---: | ---: | ---: |
| Compose UI | 10 | 4.2 ms | 5.3 ms | 5.7 ms |
| Player alone | 20 | 3.9 ms | 7.7 ms | 14.0 ms |
| Livery | 23 | 4.8 ms | 7.7 ms | 11.7 ms |
| Livery, no deferral | 21 | 5.0 ms | 6.9 ms | 9.2 ms |
| Livery, no flat layout | 21 | 5.1 ms | 7.5 ms | 10.2 ms |

The Remote Compose lanes draw about two frames per change where the twin draws one. The traces show two
recompositions per change in the Livery lane against one in the twin's. That is consistent with the data
being pushed into the player from a side effect, then the player drawing it; it is a real cost of that
path, and the cause is not yet confirmed. The Compose UI twin has the lowest and tightest frame times
here.

### Cold start

Process start to the first frame with the document in it (`timeToFullDisplayMs`). *Initial display* is
shown for completeness: for the Remote Compose lanes it is an earlier, empty frame, so it understates the time.

| Lane | Full display | Initial display |
| --- | ---: | ---: |
| Compose UI | 196 ms | 184 ms |
| Player alone | 234 ms | 148 ms |
| Livery | 232 ms | 146 ms |
| Livery, no deferral | 230 ms | 154 ms |
| Livery, no flat layout | 245 ms | 149 ms |
| Livery, preparing in composition | 222 ms | 145 ms |

The Compose UI twin reaches a drawn document about 35 ms before the Remote Compose lanes, which sit within
15 ms of each other. The Livery lanes also plan ten data variants before their first draw (nine more than one
plan needs), which is in these numbers.

### Notes on the host

- **No emoji initializer.** AndroidX Compose depends on `emoji2`, whose startup initializer loads the emoji
  font about half a second after the first frame and makes Compose text re-measure. Nothing here draws emoji,
  and it landed in the middle of what is measured: an earlier run showed every lane, the twin included, at
  about 690 ms to full display for that reason alone. The host removes the initializer; real apps keep it,
  and its cost is not Livery's.
- **Reported fully drawn after the document draws.** The activity asks for one more frame after
  `reportFullyDrawn()`, since `StartupTimingMetric` ends at the next frame drawn.

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
