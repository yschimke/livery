# Players benchmark: Livery with the AndroidX players

Pixel 9, Android 17 (API 37), release build with its baseline profile, rc-players 3.5.1, `androidx.compose.remote` 1.0.0-alpha23,
14 November 2026, with #91's startup (`livery`) in the host. Same host, device and cards as
[`site/pages/benchmarks.md`](../../site/pages/benchmarks.md); this page is not on the public site. Each lane is Livery's
`LiverySlot` with a different `LiveryHost.player` ([`../PLAYERS.md`](../PLAYERS.md)), except the Compose UI twin.
Numbers are a single run (5 iterations for startup and data change, one fling of the 200-card feed per lane); differences
under about 10 ms are noise.

## Which players draw

| Lane | Player | Draws the sample templates? |
| --- | --- | --- |
| `livery` | rc-players' Compose player (default) | yes |
| `livery-embedded` | AndroidX embedded (`RcPlayer`), pure Compose | yes |
| `livery-view` | AndroidX View (`RemoteComposePlayer`) | **no**: nothing is painted, so it has no numbers |

The View player was given the template's bytes, the values and a width, through its own API and through AndroidX's
`RemoteDocumentPlayer` wrapper, with the theme set, in a software layer and measured by hand against the document:
`CoreDocument.measure` runs, `needsMeasure` clears, the document reports 996 x 280 and a software draw of the view has one
colour. The document is not the problem (the embedded player draws it; `canBeDisplayed` is true; its profile matches the
player's). The player never measures a document that sets feature 15 itself (`RemoteComposeView.onMeasure` skips it), so
I measured it from outside; that was not enough. The cause is not found. Next: ask the AndroidX team or try a document
created by AndroidX's own creation library on the same player.

## Cold start, the document (poll card), 5 iterations, min to max

| Lane | First frame (`timeToInitialDisplayMs`) | Fully drawn (`timeToFullDisplayMs`) |
| --- | --- | --- |
| `livery` (startup holds the splash) | 142 to 173 ms | 192 to 206 ms |
| `livery-nostartup` (first-frame budget) | 189 to 217 ms | 223 to 246 ms |
| `livery-embedded` | 140 to 162 ms | 189 to 200 ms |
| Compose UI twin | 168 to 179 ms | 182 to 194 ms |

The embedded player starts as fast as the default one; the cost of startup is Livery's own preparation, not the player. The
startup path of #91 is about 47 ms ahead of the first-frame-budget path at the first frame.

## Data change (a tap pushes new data), `FrameTimingMetric`

| Lane | frame CPU p50 | p90 | p99 | frames |
| --- | --- | --- | --- | --- |
| `livery` | 5.0 ms | 9.7 ms | 12.7 ms | 20 |
| `livery-embedded` | 6.0 ms | 10.8 ms | 13.5 ms | 42 |
| Compose UI twin | 4.2 ms | 5.3 ms | 6.5 ms | 10 |

The embedded player draws about twice the frames for the same taps (its change animates over more frames, or recomposes
more), at a similar cost per frame.

## Feed fling (200 cards, banner and poll)

| Lane | frame CPU p50 | p90 | p99 | frame overrun p99 | frames |
| --- | --- | --- | --- | --- | --- |
| `livery` | 3.9 ms | 6.2 ms | 8.8 ms | -5.3 ms | 656 |
| `livery-embedded` | 3.7 ms | 7.7 ms | 11.6 ms | -2.2 ms | 682 |
| Compose UI twin | 4.0 ms | 6.3 ms | 8.3 ms | -6.0 ms | 656 |

The embedded player is level with the default at the median and 25 to 30% slower in the tail (p90 7.7 against 6.2 ms,
p99 11.6 against 8.8 ms), with a smaller margin before a frame is missed. The default player in Livery is level with the
Compose UI twin through p95. No lane missed a frame (overrun stays negative).

## Not measured

- The View player (does not draw).
- The players alone, without Livery (`rc` is the CMP player alone; no `view` or `embedded` lanes yet).
- Images and Livery's catalog fonts on the AndroidX players (not adapted), so a card with either is not comparable.
- Pixel parity of the embedded player with the twin (looked right by eye on the poll card; not checked per pixel).
