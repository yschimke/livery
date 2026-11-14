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

The feed the fling benchmark scrolls, whole, as the benchmark host draws it on the phone: 200 cards,
alternating a promo banner and a poll, the Compose UI twin on the left and Livery on the right.

<div style="display:flex;gap:1rem;flex-wrap:wrap">
<figure style="margin:0"><img src="assets/benchmarks/feed-compose-ui.png" alt="The feed drawn in Compose UI on the Pixel 9" width="270" height="606" style="border:1px solid #8884"><figcaption>Compose UI twin</figcaption></figure>
<figure style="margin:0"><img src="assets/benchmarks/feed-livery.png" alt="The same feed drawn by Livery on the Pixel 9" width="270" height="606" style="border:1px solid #8884"><figcaption>Livery</figcaption></figure>
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

The single-document screen, as drawn on the phone (Compose UI twin, then Livery). The sample template takes
its own height, so the card sits at the top of a full screen; a design that fills the screen needs its own twin.

<div style="display:flex;gap:1rem;flex-wrap:wrap">
<figure style="margin:0"><img src="assets/benchmarks/document-compose-ui.png" alt="The poll card drawn in Compose UI on the Pixel 9" width="180" height="404" style="border:1px solid #8884"><figcaption>Compose UI twin</figcaption></figure>
<figure style="margin:0"><img src="assets/benchmarks/document-livery.png" alt="The same poll card drawn by Livery on the Pixel 9" width="180" height="404" style="border:1px solid #8884"><figcaption>Livery</figcaption></figure>
</div>


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

## What the traces show

The numbers above say how long; Perfetto traces of the same runs say where it went. Each benchmark iteration
records one, and `livery-baselineprofile/traces/compare.py` takes the median over the five of a lane and puts
the Compose UI twin and Livery (defaults) side by side. Everything below comes from it, and the tables are its
output. (The `perfetto-traces` agent skill in `.claude/skills/` describes how to read these traces.)

### Cold start

<img src="assets/benchmarks/startup-timeline.svg" alt="Cold start timeline: Compose UI reaches full display at 197 ms, Livery at 233 ms" style="max-width:100%;height:auto">

| Cold start (ms from the launch intent) | Compose UI | Livery | Difference |
| --- | ---: | ---: | ---: |
| bindApplication ends | 103.7 | 94.9 | -8.8 |
| activity resumed | 130.3 | 122.2 | -8.1 |
| first frame starts | 136.8 | 133.9 | -2.9 |
| document drawn on screen (first RenderThread frame with content) starts | 150.7 | 195.8 | +45.1 |
| reportFullyDrawn | 186.7 | 200.4 | +13.7 |
| full display (end of the first frame drawn after it) | 196.9 | 232.6 | +35.7 |
| frames drawn before that | 2 | 3 | +1 |
| main thread: Compose initializeView | 2.2 | 2.3 | +0.1 |
| main thread: recompose | 0.7 | 5.1 | +4.4 |
| main thread: measure | 4.3 | 6.4 | +2.1 |
| main thread: text layout | 0.2 | 0 | -0.2 |
| RenderThread: frame that draws the content | 30.2 | 36.6 | +6.4 |
| RenderThread: GPU pipeline compiles | 20.7 | 24.8 | +4.1 |
| RenderThread: GPU pipeline compile count | 4 | 4 | 0 |


- **The first 130 ms are the same.** Process start, app initialisation and creating the activity run the same code
  in both lanes; the differences between them, up to about 10 ms, are run-to-run noise.
- **Compose UI draws the document in its first frame.** The twin composes, measures and records in that one frame
  (14 ms from its start to the GPU) and the screen is complete after two frames.
- **Livery's first frame is empty, and the document arrives a frame later.** The first frame starts at about the
  same time, but the content frame is 62 ms after it, not 14, and three frames are drawn, not two. In that gap, in the
  trace inspected, the main thread shows no slice longer than a millisecond: the host plans its ten data variants and prepares the
  document, off the main thread, and nothing traces that work. Then composition takes 5 ms (against under 1) and
  measure 6 ms (against 4). Part of the gap is the benchmark host's own planning of ten variants rather than
  Livery's cost for one; the planned `livery:plan` and `livery:prepare` trace sections are what would separate them.
- **The GPU's share is the same.** Both lanes compile four pipelines the first time the screen is drawn, 21 ms
  against 25 ms. That is the driver, and it is the same cost for any lane.

### Fling

<img src="assets/benchmarks/fling-per-frame.svg" alt="Fling per frame: main thread busy 3.21 ms for Compose UI and 3.12 ms for Livery; RenderThread draw 2.46 ms and 1.95 ms" style="max-width:100%;height:auto">

| Fling (per frame) | Compose UI | Livery | Difference |
| --- | ---: | ---: | ---: |
| frames | 661 | 670 | +9 |
| in the frame: doFrame ms, mean | 2.02 | 2.02 | 0 |
| in the frame: doFrame ms, p90 | 3.12 | 3.72 | +0.6 |
| in the frame: doFrame ms, p99 | 5.22 | 5.96 | +0.74 |
| in the frame, per frame ms: animation (recomposition, effects) | 0.711 | 0.702 | -0.009 |
| in the frame, per frame ms: draw recording (draw-VRI) | 0.92 | 0.786 | -0.134 |
| in the frame, per frame ms: measure and layout | 0.494 | 0.538 | +0.044 |
| in the frame, per frame ms: RenderThread draw (DrawFrames) | 2.457 | 1.95 | -0.507 |
| whole window, per frame ms: main thread busy (all top-level slices) | 3.213 | 3.115 | -0.098 |
| whole window, per frame ms: of which outside doFrame (prefetch, input, other) | 1.159 | 1.099 | -0.06 |
| whole window, per frame ms: Recomposer:recompose | 0.015 | 0.206 | +0.191 |
| whole window, per frame count: Compose:recompose passes | 0.18 | 0.33 | +0.15 |
| whole window, per frame count: text layouts (TextLayout:initLayout) | 1.28 | 1.91 | +0.63 |
| whole window, per frame ms: text layout | 0.138 | 0.055 | -0.083 |


- **The main thread does about the same work per frame.** 3.2 ms against 3.1 ms busy over the whole fling, and
  2.0 ms in the frame itself in both lanes. Livery's slowest frames run a little longer (p99 6.0 ms against 5.2 ms).
- **The work sits in different places.** The twin's lazy list appears to compose its upcoming items between frames and
  in measure (its recomposer time is close to nothing); Livery's cards show up as composition passes in the recomposer (0.21 ms per frame against 0.02),
  consistent with each card's plan arriving after its first composition. That is an observation about where the
  time is, not a cost the lane pays on top.
- **Text is laid out more often, and for less.** 1.9 layouts per frame against 1.3, taking 0.06 ms against 0.14.
- **The render thread does less for Livery** (1.95 ms a frame against 2.46). We have not investigated why.

What the traces cannot tell us: they show the main thread and the render thread. Planning, document preparation
and font loading run on other threads without trace sections, so what they cost, and what Livery's first frame
is waiting for, is unmeasured until those sections exist.

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
