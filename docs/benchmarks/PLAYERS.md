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
I measured it from outside; that was not enough. Handing it the document rewritten for the display (`forDisplay`: density
hardcoded, constants folded, as the View player is said not to evaluate density expressions) did not change it either, alone or
together with the manual measure. The cause is not found. Next: ask the AndroidX team or try a document
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

## Feed fling (200 cards, banner and poll), with #93's prefetch options

A second, longer run (all nine lanes in one session) supersedes the first fling numbers. Frame CPU p50 / p90 / p99, ms:

| Lane | default prefetch | cache window (1 ahead, 0.5 behind) | pausable composition |
| --- | --- | --- | --- |
| `livery` | 3.8 / 5.9 / 8.3 | 3.8 / 6.2 / 8.6 | 3.7 / 5.6 / 8.1 |
| Compose UI twin | 3.7 / 5.8 / 8.3 | 3.8 / 6.0 / 8.4 | 3.8 / 6.1 / 8.4 |
| `livery-embedded` | 3.9 / 8.0 / 11.8 | 3.9 / 8.2 / 12.1 | 3.6 / 7.4 / 11.6 |

- Livery's default player is level with the Compose UI twin in every column, so neither option separates them.
- The embedded player is level at the median and about 35% slower at p90 and 40% at p99, under every option: the cost is
  the player, and prefetch does not move it. Its worst frames are the closest to a missed frame (p99 overrun -1.7 to -2.5
  ms against -5.6 to -6.1 for the others). No lane missed one.
- The options change frame counts by a few percent either way, which is noise next to the tail difference.

## Not measured

- The View player (does not draw).
- The players alone, without Livery (`rc` is the CMP player alone; no `view` or `embedded` lanes yet).
- Images and Livery's catalog fonts on the AndroidX players (not adapted), so a card with either is not comparable.
- Pixel parity of the embedded player with the twin (looked right by eye on the poll card; not checked per pixel).
