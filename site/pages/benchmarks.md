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
| Livery | `LiverySlot` / `LiveryTemplate` with the defaults: background preparation, deferred layout animations, flat layout. Its first screen is prepared by Livery's `startup`, with the splash screen up until it is ready, as getting-started has it |
| Livery, no startup | The same with no `startup`: the slot plans as it first composes, and its first frame waits within the first-frame budget (start-up only) |
| Livery, no deferral | `deferLayoutAnimations = false` (flat layout needs the deferral, so it is off too) |
| Livery, no flat layout | `flattenLayout = false` |
| Livery, lazy | Nothing prepared before the list shows: each slot plans as it composes, and `LiveryPrepareAhead` keeps the next 8 cards prepared (planning made to take 20 ms a card, as an app's own work would) |
| Livery, paged | The same over an endless androidx Paging feed, pages of 20 taking 300 ms |

## Latest run: 15 November

**Pixel 9, Android 17 (API 37), rc-players main built from source (3.5.4-SNAPSHOT: the per-value invalidation changes, not yet released), 15 November 2026**, `benchmarkRelease` with the shipped baseline
profiles. One device, lanes run one after another. The tests are a T: a **spine** of the variants an app would choose
between, run more (feed: 5 iterations of 4 flings; start-up: 10; data change: 8), and a short bar of the rest for
completeness (3 iterations of 3 flings), which says whether a lane is wildly off and not by how much. The first run
below (14 November) is the one the trace analysis explains.

### Feed fling

Frame CPU p50 / p90 / p99, and the margin before a missed frame (overrun p99; negative is time to spare). No lane
here missed a frame.

| Lane | CPU p50 | CPU p90 | CPU p99 | Overrun p99 |
| --- | ---: | ---: | ---: | ---: |
| Compose UI | 3.8 ms | 5.8 ms | 8.1 ms | −6.1 ms |
| Livery, everything prepared before the list shows | 3.4 ms | 5.5 ms | 8.0 ms | −6.1 ms |
| Livery, lazy: next 8 cards prepared ahead | 3.7 ms | 6.4 ms | 8.7 ms | −5.3 ms |
| Livery, paged (androidx Paging), next 8 ahead | 3.3 ms | 5.6 ms | 8.1 ms | −5.9 ms |

Livery is level with the Compose UI twin, through p99, whether it prepares everything first or only the cards just
ahead of the scroll, and over a paged feed. The other lanes (3 iterations, so read them as "not far off"): no deferral
3.9 / 6.1 / 8.3 ms, no flat layout 3.6 / 6.0 / 8.8, and the player alone 3.6 / 5.9 / 7.8.

**Preparing ahead is what keeps a lazy feed level.** A card that scrolls in before its plan is ready draws the host's
placeholder and swaps when it arrives. Counting the placeholders that became visible (5 iterations of 6 flings, planning
made to take 20 ms a card): with each slot planning as it composes, **18** cards reached the screen unprepared and p99
was 11.0 ms; with a plan three times slower, **108** and 12.2 ms; the paged feed, **15** and 10.5 ms. With
`LiveryPrepareAhead` and a window of 2, 4 or 8 cards: **0** in every case, p99 8.3 to 8.9 ms, and 8 still held at the
60 ms plan. The window has to cover the planning time of the fastest fling (about 6 cards a second here); the default
is 8. (The short bar's shorter flings agree: 9 and 10 unprepared cards without a window.)

**Compose's prefetch options did not help.** Livery with a cache window (one viewport ahead, half behind) was
3.5 / 5.8 / 8.8 ms and with pausable composition in prefetch 3.4 / 5.6 / 7.9, against 3.8 / 6.0 / 8.4 without either;
the Compose UI twin, measured the same way earlier that day, 3.8 / 6.0 / 8.4 and 3.8 / 6.1 / 8.4 against 3.7 / 5.8 / 8.3.
This feed prepares every card before it shows, so prefetch has little left to hide; a feed whose plans arrive late is
what the lazy lanes above are for. (Compose turned pausable composition off by default in 1.10.6 for stability, and
removed the flag in 1.13.0-alpha03; this measures it, it doesn't recommend it.)

### Animated feed

The visible polls' numbers change every second while the list flings. Two things in the templates and the twin decide
how much work that is, so the lanes come in pairs doing the same visible work. The catalog build turns a template's easing off
unless its author asked for it (`"defaultAnimations": true`), so a bar lands at once; the twin's bars can do either.

| Lane | CPU p50 | CPU p90 | CPU p99 | Overrun p99 |
| --- | ---: | ---: | ---: | ---: |
| Compose UI, bars land at once | 3.1 ms | 5.1 ms | 12.2 ms | −1.3 ms |
| Livery, bars land at once (the default) | 3.2 ms | 7.1 ms | **10.3 ms** | −2.5 ms |
| Compose UI, bars ease | 3.3 ms | 5.7 ms | 12.2 ms | −1.1 ms |
| Livery, bars ease, default deferral | 4.6 ms | 8.3 ms | 16.6 ms | +2.5 ms |
| Livery, bars ease, no deferral | 3.7 ms | 7.1 ms | **10.5 ms** | −2.4 ms |

**Where the templates' bars land at once, Livery's p99 is below the twin's (10.3 against 12.2 ms) and misses no frame**,
with a higher p90 (7.1 against 5.1 ms): each update costs it more, but without the long frames. (Before the changes in
Livery's slot and in rc-players that made a value change recompose, measure and draw only what read it, the same lane's p99 was
21.7 ms.)

**Where the bars ease, Livery's default is slower and misses frames: 16.6 ms at p99 against the twin's 12.2.** The cost is layout:
17 long `measureAndLayout` passes in the run (116 ms in all, the longest 15.7 ms), in which about 33 texts are measured again on
every frame of a bar's easing though no text changed, and compositions run inside the measure pass. The twin has no pass over
4 ms. It is Livery's `deferLayoutAnimations` (the default): it skips AndroidX's lookahead pass until a card's data first changes, and
a card that changes every second keeps paying for it. **Turned off, the eased lane's p99 is 10.5 ms, under the twin's** (3 long
passes, 15 ms in all). The option is the right default for a list that scrolls and rarely changes (a static feed is a little cheaper with it: p50 3.4
against 3.9 ms) and the wrong one for cards that update on a schedule: `LiveryHost(deferLayoutAnimations = false)`.

Livery also used to flicker here: a slot whose data changed drew the placeholder while it planned the new answer (166
times in a run). A slot now keeps its card until the new answer is ready (and `LiverySlot` takes an `identity` for which
card it is), and the placeholders are 0. The split of what each fix gave is in
[`docs/benchmarks/LAZY.md`](https://github.com/yschimke/livery/blob/main/docs/benchmarks/LAZY.md).

### Full-screen document: changing data and cold start

Data change, 10 taps (`FrameTimingMetric`): Compose UI 4.1 / 5.5 / 8.1 ms over 9 frames, Livery 5.1 / 10.3 / 11.4 ms
over 19: two frames a change where the twin draws one, as in the first run.

Cold start, process start to the first frame with the document in it, 10 iterations, median (range):

| Lane | Initial display | Full display |
| --- | ---: | ---: |
| Livery (`startup`, splash screen held) | 147 ms (139–231) | 221 ms (197–254) |
| Livery, no `startup` (first-frame budget) | 208 ms (189–241) | 240 ms (221–272) |
| Compose UI | 186 ms (157–201) | 197 ms (165–217) |

With `startup` Livery's first frame is the one with its card in it, and it arrives at or before the twin's (147 against 186 ms at the median);
the first-frame budget without `startup` holds it until the slot's answer is there, about 60 ms later at the median. Livery's
`startup` itself took 21 ms on a worker thread, and the splash screen was held for it by 0 ms in the median: it fits in the
activity's creation on this phone. The ranges are wide and overlap (Livery's initial display has run from 131 to 231 ms
across runs, and the twin's from 157 to 231), so with ten runs per lane the order of Livery with `startup` and the
twin is a tendency, not a result. The other lanes (3 iterations; initial / full display): no deferral 133 / 213 ms,
no flat layout 206 / 233, preparing in composition 204 / 235. The player alone's first frame (140 ms) is an empty one, drawn
before its document is ready, so it has no comparable initial display; its full display is 240 ms.

## First run: 14 November

**Pixel 9, Android 17 (API 37), rc-players 3.3.0, 14 November 2026.** Macrobenchmark on a
`benchmarkRelease` build with the shipped baseline profiles, 5 iterations per test. One device, lanes run
one after another rather than interleaved: differences of a few tenths of a millisecond are inside the
noise, and are shown so you can see that, not as a ranking. The whole suite was run twice on this phone (the
second run, shown here, with the host holding its first frame; see the notes on the host), and where the
first run says something different it is mentioned.

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
| Compose UI | 652 | 3.7 ms | 5.8 ms | 8.3 ms | −6.2 ms | 1.00× |
| Player alone | 659 | 4.3 ms | 6.8 ms | 9.3 ms | −5.0 ms | 1.16× |
| Livery | 660 | 3.5 ms | 6.1 ms | 9.0 ms | −5.1 ms | 0.95× |
| Livery, no deferral | 673 | 4.1 ms | 6.6 ms | 8.8 ms | −5.1 ms | 1.11× |
| Livery, no flat layout | 660 | 3.7 ms | 5.9 ms | 8.6 ms | −5.6 ms | 1.00× |

What this says: on a current phone no lane misses a frame (every overrun is negative), and the lanes sit
within about 0.8 ms of each other at the median. The order of the medians was the same in both runs: Livery
with its defaults lowest, the player alone and Livery without the deferral highest, and the Compose UI twin and
Livery without flat layout between them (the first run's medians were 3.9, 4.1, 3.4, 4.3 and 3.8 ms, in the
table's order). Turning the deferral off moves Livery to the player-alone figure, the direction it is meant to
move. With one device and five iterations per run, this does not show that Livery is faster or slower than
Compose UI.

**Compose's prefetch options.** Two more pairs of lanes fling Livery and the Compose UI twin with one of
Compose's own prefetch options on, so each is measured for both (neither is Livery's to set: the cache window
belongs to the app's list, the flag to Compose):

| Option | What it does |
| --- | --- |
| Cache window (`LazyLayoutCacheWindow`, one viewport ahead, half behind) | Cards ahead of the viewport are composed and measured in prefetch, between frames, before they scroll in; cards behind stay composed for a scroll back. The default is one item ahead |
| Pausable composition in prefetch (`ComposeFoundationFlags.isPausableCompositionInPrefetchEnabled`) | Prefetch can compose an upcoming card across several frames' idle time instead of in one go. Compose turned it off by default in 1.10.6 for stability; this lane measures it, it doesn't recommend it |

Their numbers are in the latest run above.

### Full-screen document: changing data

One template (`poll-card`) in a padded, top-aligned, full-width frame; the sample template takes its
own height, so this is a card on a full screen. Tapping the document shows the next of ten data variants,
ten times. Every variant is bound to the contract before the screen shows, so the tap itself costs each
lane one swap of state; the cost of binding data is a separate measurement, still *Planned*.

The single-document screen, as drawn on the phone (Compose UI twin, then Livery):

<div style="display:flex;gap:1rem;flex-wrap:wrap">
<figure style="margin:0"><img src="assets/benchmarks/document-compose-ui.png" alt="The poll card drawn in Compose UI on the Pixel 9" width="180" height="404" style="border:1px solid #8884"><figcaption>Compose UI twin</figcaption></figure>
<figure style="margin:0"><img src="assets/benchmarks/document-livery.png" alt="The same poll card drawn by Livery on the Pixel 9" width="180" height="404" style="border:1px solid #8884"><figcaption>Livery</figcaption></figure>
</div>

| Lane | Frames over 10 changes | CPU p50 | CPU p90 | CPU p99 |
| --- | ---: | ---: | ---: | ---: |
| Compose UI | 10 | 4.3 ms | 5.7 ms | 8.6 ms |
| Player alone | 19 | 3.9 ms | 7.6 ms | 10.5 ms |
| Livery | 22 | 4.9 ms | 7.7 ms | 12.7 ms |
| Livery, no deferral | 20 | 5.0 ms | 7.8 ms | 10.4 ms |
| Livery, no flat layout | 22 | 4.9 ms | 7.5 ms | 10.6 ms |

The Remote Compose lanes draw about two frames per change where the twin draws one. The traces show why in
count: ten recompositions for ten changes in the twin, twenty for the player alone and twenty-one for Livery
(the same in all five iterations). The player alone has none of Livery's code in the path, so the second pass
comes from the Remote Compose player (or from how the host hands it new values), not from `LiveryTemplate`.
Why the player recomposes twice is not yet known. Per-frame cost is comparable, and the twin's tail is lower.

### Cold start

Process start to the first frame with the document in it. In this run the host held its first frame until the
document could draw (see the notes below), so *initial display* is that frame on every lane, and *full display* is
the frame after `reportFullyDrawn()`.

| Lane | Initial display | Full display |
| --- | ---: | ---: |
| Compose UI | 191 ms | 194 ms |
| Player alone | 223 ms | 225 ms |
| Livery | 218 ms | 220 ms |
| Livery, no deferral | 225 ms | 227 ms |
| Livery, no flat layout | 234 ms | 236 ms |
| Livery, preparing in composition | 217 ms | 219 ms |

The host no longer holds that frame itself. Its Livery lanes are a plain `LiverySlot`, as an app writes one. The
`livery` lanes prepare their first screen with Livery's `startup` in `onCreate` and keep the splash screen up until it
is ready, as getting-started tells an app to; `livery-nostartup` has no `startup`, and Livery's first-frame budget
(`LiveryHost.firstFrameBudgetMillis`, 150 ms) holds its first frame for the slot's answer instead. The Compose UI
twin builds nothing of Livery, where in this run it also read the bundled catalog. The latest run above is these
numbers again with that host.

The Compose UI twin reaches a drawn document about 26 ms before Livery (194 against 220 ms), and the Remote Compose
lanes sit between 219 and 236 ms, within 20 ms of each other, which with one run per lane is not a ranking. The Livery
lanes also plan ten data variants before their first draw (nine more than one plan needs), which is in these numbers.

### Notes on the host

- **No emoji initializer.** AndroidX Compose depends on `emoji2`, whose startup initializer loads the emoji
  font about half a second after the first frame and makes Compose text re-measure. In an earlier run that
  put every lane, the twin included, at about 690 ms to full display for that reason alone. Livery's own
  manifest now removes the initializer from any app that uses it, so no lane here has it. Livery loads
  emoji itself two frames after its first template draws (`LiveryEmoji`), as it does in an app. That
  happens during a Livery lane's run, and never in the Compose UI twin's or the player alone's.
- **The first frame was held, in the run above, by the host.** The activity vetoed drawing (an
  `OnPreDrawListener`) until the document was composed, as an app keeps its splash screen up. Before that, the
  Remote Compose lanes drew an empty frame while they planned, so their *initial display* was an earlier, empty
  frame (about 150 ms against a full display of about 230 ms). The host now leaves this to Livery, as an app
  would: its startup and the splash screen, or, in `livery-nostartup`, the first-frame budget.
- **Every lane installs the splash screen.** The activity's starting theme is core-splashscreen's in every lane,
  so they all run the same activity code; only the Livery lanes with `startup` keep it up.
- **The twin builds nothing of Livery.** In the runs above the host built Livery's repository in every lane, and
  read the bundled catalog's manifest on the main thread (3.8 ms) in the twin's lane too. Livery now reads it when
  a template is first resolved, and the twin's lane builds no repository.
- **Reported fully drawn after the document draws.** The Compose UI and player lanes report after the
  frame that composed the document. The Livery lanes report on Livery's `Impression` event, which already
  comes after the template's first frame, so they don't wait a frame more. Every lane then asks for one
  more frame after `reportFullyDrawn()`, since `StartupTimingMetric` ends at the next frame drawn.

## What the traces show

The numbers above say how long; Perfetto traces of the same runs say where it went. Each benchmark iteration
records one, and `livery-baselineprofile/traces/compare.py` takes the median over the five of a lane and puts
the Compose UI twin and Livery (defaults) side by side. The tables and the charts below are its output, and the
Perfetto UI screenshots are `livery-baselineprofile/traces/screenshots.py`'s. (The `perfetto-traces` agent skill
in `.claude/skills/` describes how to read these traces.)

### Cold start

<img src="assets/benchmarks/startup-timeline.svg" alt="Cold start timeline: Compose UI reaches full display at 194 ms, Livery at 220 ms" style="max-width:100%;height:auto">

| Cold start (ms from the launch intent) | Compose UI | Livery | Difference |
| --- | ---: | ---: | ---: |
| bindApplication ends | 100.4 | 97.4 | -3 |
| activity resumed | 127.7 | 124.5 | -3.2 |
| first frame starts | 138.8 | 131.3 | -7.5 |
| document drawn on screen (RenderThread frame with content) starts | 156.8 | 192.2 | +35.4 |
| reportFullyDrawn | 192 | 218.6 | +26.6 |
| full display (end of the first frame drawn after it) | 194 | 220.3 | +26.3 |
| frames drawn before that | 2 | 2 | 0 |
| main thread: Compose initializeView | 2.4 | 2.2 | -0.2 |
| main thread: recompose | 0.7 | 2.9 | +2.2 |
| main thread: measure | 4.3 | 5 | +0.7 |
| main thread: text layout | 0.2 | 0 | -0.2 |
| RenderThread: frame that draws the content | 37 | 31.8 | -5.2 |
| RenderThread: GPU pipeline compiles | 27.3 | 22.6 | -4.7 |
| RenderThread: GPU pipeline compile count | 4 | 4 | 0 |

- **The first 125 ms are the same.** Process start, app initialisation and creating the activity run the same code in
  both lanes: `bindApplication` ends at 100 ms against 97 ms, and the activity is resumed at 128 ms against 125 ms.
- **Both lanes held their first frame (the host's hold, in this run); Livery's content arrives later.** The twin's first frame starts at 139 ms
  and its content is on the GPU 18 ms later. Livery's first frame callback starts at 131 ms, measures and lays out,
  and is held with nothing drawn (the Perfetto view below shows these traversals), and its content reaches the GPU at
  192 ms, 61 ms later. In that stretch the main thread shows no slice longer than a millisecond, because the work is
  on a worker thread: Livery plans the host's ten data variants and prepares the document there. Livery's own trace
  sections say where those milliseconds go (the next subsection).
- **Then Livery's main thread does a little more.** Composition takes 2.9 ms (against 0.7) and measure 5.0 ms
  (against 4.3). Each lane draws two frames before full display; before the first frame was held Livery drew three.
- **The GPU's share is the same.** Both lanes compile four pipelines the first time the screen is drawn, 23 ms
  against 27 ms, and the render thread's content frame takes 32 ms against 37. That is the driver, not the template.

### Where Livery's cold start goes

Livery marks its own phases in the trace (`livery:<phase>:<design>`, see the notes below), including work on other
threads. A later run of the two start-up lanes with them on, median of five (the cold-start numbers above are from the
run before, so the totals differ by a few milliseconds of run-to-run noise):

| Livery section | Count | Total |
| --- | ---: | ---: |
| `livery:manifest-decode` (the bundled catalog's manifest, on the main thread at app start; both lanes) | 1 | 3.8 ms |
| `livery:plan` (planning a card's data) | 10 | 3.5 ms |
| of which `livery:resolve` (choosing and reading the template) | 10 | 3.0 ms |
| of which `livery:asset-read` (the bundled blob; the same one each time) | 10 | 1.0 ms |
| of which `livery:bind` (data against the contract) | 10 | 0.5 ms |
| `livery:ready`, which is `livery:prepare` (the document) and the fonts (none here) | 1 | 15.7 ms |
| of which `livery:for-display` (the document rewritten for this display's density) | 1 | 9.1 ms |
| `livery:compose` (the card's composition, on the main thread) | 1 | 2.6 ms |

In order, in the iteration closest to the median (milliseconds after the launch intent): the ten plans run from
140.0 to 144.2 ms (the first takes 1.4 ms, the rest about 0.2 ms each); the document is prepared from 144.3 to
158.9 ms, of which the `for-display` rewrite is 146.3 to 154.9 ms (8.6 ms), with about 2 ms of decode and link before it
and about 4 ms after; then the main thread composes the card. So the worker's 15 ms of CPU is almost all document
preparation, and more than half of that is the density rewrite. The host's nine extra plans cost about 3 ms, much
less than the gap to the Compose UI twin, so the ten-variant caveat above is small.

Two things this suggests, neither tried: the rewrite and the decode could run before the first frame (an app does this
with Livery's startup API, which prepares at process start; this host prepares after the activity exists), and a cold
rewrite of 9 ms may be partly code that was not yet compiled, which a baseline profile of that path would show.

### Fling

<img src="assets/benchmarks/fling-per-frame.svg" alt="Fling per frame: main thread busy 2.91 ms for Compose UI and 3.29 ms for Livery; RenderThread draw 2.31 ms and 2.13 ms" style="max-width:100%;height:auto">

| Fling (per frame) | Compose UI | Livery | Difference |
| --- | ---: | ---: | ---: |
| frames | 658 | 667 | +9 |
| in the frame: doFrame ms, mean | 1.85 | 2.09 | +0.24 |
| in the frame: doFrame ms, p90 | 2.87 | 3.83 | +0.96 |
| in the frame: doFrame ms, p99 | 5.39 | 6.44 | +1.05 |
| in the frame, per frame ms: animation (recomposition, effects) | 0.656 | 0.732 | +0.076 |
| in the frame, per frame ms: draw recording (draw-VRI) | 0.851 | 0.833 | -0.018 |
| in the frame, per frame ms: measure and layout | 0.459 | 0.575 | +0.116 |
| in the frame, per frame ms: RenderThread draw (DrawFrames) | 2.307 | 2.133 | -0.174 |
| whole window, per frame ms: main thread busy (all top-level slices) | 2.908 | 3.289 | +0.381 |
| whole window, per frame ms: of which outside doFrame (prefetch, input, other) | 1.056 | 1.201 | +0.145 |
| whole window, per frame ms: Recomposer:recompose | 0.015 | 0.196 | +0.181 |
| whole window, per frame count: Compose:recompose passes | 0.18 | 0.33 | +0.15 |
| whole window, per frame count: text layouts (TextLayout:initLayout) | 1.28 | 1.86 | +0.58 |
| whole window, per frame ms: text layout | 0.124 | 0.058 | -0.066 |

- **The main thread does about the same work per frame, within 0.4 ms.** 2.9 ms busy for the twin against 3.3 ms for
  Livery over the whole fling, and 1.9 ms against 2.1 ms in the frame itself. In the first run it was the other way round
  (3.2 against 3.1 ms), so which lane is ahead is not stable between runs, and the slowest frames run a little longer
  for Livery in both (p99 6.4 ms against 5.4 ms now).
- **The work sits in different places.** The twin's lazy list appears to compose its upcoming items between frames and
  in measure (its recomposer time is close to nothing); Livery's cards show up as composition passes in the recomposer
  (0.20 ms per frame against 0.02), consistent with each card's plan arriving after its first composition. That is
  an observation about where the time is, not a cost the lane pays on top.
- **Text is laid out more often, and for less.** 1.9 layouts per frame against 1.3, taking 0.06 ms against 0.12.
- **The render thread does less for Livery** (2.13 ms a frame against 2.31; 1.95 against 2.46 in the first run). We have
  not investigated why.

### In the Perfetto UI

The same runs in Perfetto's own timeline view: the Compose UI twin above, Livery below, on one scale. Each pair
shows the same span at the same size, so a millisecond is the same width in both, and the grey flags mark the same
time in both (the ruler's own times are each trace's clock). The rows are the launch, the frame timeline, the app's
main thread and its render thread; each lane is the iteration closest to its median.

Cold start, 100 to 250 ms after the launch intent. The green flag is full display:

<a href="assets/benchmarks/perfetto-startup-compose-ui.png"><img src="assets/benchmarks/perfetto-startup-compose-ui.png" alt="Perfetto UI: Compose UI cold start, the first frame drawn at about 157 ms and full display at 194 ms" style="max-width:100%;height:auto;border:1px solid #8884"></a>

<a href="assets/benchmarks/perfetto-startup-livery.png"><img src="assets/benchmarks/perfetto-startup-livery.png" alt="Perfetto UI: Livery cold start, two held traversals, then the content frame and full display at 220 ms" style="max-width:100%;height:auto;border:1px solid #8884"></a>

Six frames of the fling, 100 ms (click an image for the full size):

<a href="assets/benchmarks/perfetto-fling-compose-ui.png"><img src="assets/benchmarks/perfetto-fling-compose-ui.png" alt="Perfetto UI: six frames of the Compose UI fling" style="max-width:100%;height:auto;border:1px solid #8884"></a>

<a href="assets/benchmarks/perfetto-fling-livery.png"><img src="assets/benchmarks/perfetto-fling-livery.png" alt="Perfetto UI: six frames of the Livery fling" style="max-width:100%;height:auto;border:1px solid #8884"></a>

What the traces still cannot tell us: inside `prepare`, rc-players' decode and link. Its published player has no trace
spans of its own (3.3.0), so that part is only the difference between Livery's `prepare` and the `for-display` section
inside it.

## Livery's trace sections

`LiveryTrace` (in `livery-client`) writes named sections to the system trace on Android and does nothing elsewhere; a
section's name is `livery:<phase>[:<design or contract>]` and never carries data. Synchronous ones sit on their thread's
track; ones that can resume on another thread (planning, resolving, preparing, fonts, image fetches) are async tracks.

| Section | Wraps |
| --- | --- |
| `livery:startup` | an app's `startup`, preparing its first screen (from the call to ready) |
| `livery:plan:<design>` | `SlotPlanner` choosing a template and binding data for a slot, decision or feed item |
| `livery:resolve:<design>` | the repository choosing the template and reading its bytes |
| `livery:asset-read:<sha>`, `livery:manifest-decode` | the bundled catalog's blobs and manifest (Android; the manifest is decoded where it is first needed) |
| `livery:bind:<contract>` | `ContractBinder.bind`, data against the contract |
| `livery:prepare:<design>` | the player's document preparation: decode, link, rewrite |
| `livery:for-display` | the rewrite for the display, inside `prepare` |
| `livery:ready:<design>` | a card's document and fonts ready (`readyToDraw`) |
| `livery:fonts:<design>`, `livery:font-load:<sha>` | catalog fonts read and loaded |
| `livery:image-resolve:<kind>`, `livery:image-decode` | an image's bytes and their decode |
| `livery:compose:<design>` | a card's own composition: its first, and each time its prepared document or fonts arrive; not later recompositions inside the player's own scopes |

`livery-baselineprofile/traces/compare.py` totals them per phase for the cold-start window.

## What is not measured yet

*Planned*: a warm second visit; idle frames; resize and theme change; a
per-template split and counts of documents prepared and reused; background preparation traces; memory;
baseline profile on against off; a low-end device. The plan, in order, is
[`docs/benchmarks/PLAN.md`](https://github.com/yschimke/livery/blob/main/docs/benchmarks/PLAN.md).

## Reproduce

With a phone connected (a device with a low battery is refused, as it throttles):

```sh
./gradlew :livery-benchmark:connectedBenchmarkReleaseAndroidTest
```

or, tiered, through `adb` (about 40 minutes for the routine set, a third of every variant): the spine and the short bar
of the rest with `livery-baselineprofile/run-benchmarks.sh routine`, the spine alone with `spine`, and the sweeps for
working out a value (window sizes, planning costs) with `sweeps`.

The tables under "What the traces show" are `python3 livery-baselineprofile/traces/compare.py <the run's
traces> --svg site/assets/benchmarks`, and the Perfetto UI screenshots are
`livery-baselineprofile/traces/screenshots.py`, which serves the official Perfetto UI (checked against its
published hashes) and the traces from your own machine and writes only the images. The raw medians behind these
tables are in
[`docs/benchmarks/results/`](https://github.com/yschimke/livery/tree/main/docs/benchmarks/results).
The host app and benchmarks are in `livery-baselineprofile/`; its README describes each lane. A
JVM profile (`:livery-compose:liveryFeedProfile`) gives the same comparison without a device, with
software rendering, for quick iteration.
