# Lazy, paged and animated feeds

Pixel 9, Android 17 (API 37), Livery `main`, rc-players 3.6.1. The numbers are in
[`site/pages/benchmarks.md`](../../site/pages/benchmarks.md) and the medians behind them in
[`results/`](results/); this page is what the lanes are and what they show.

## Lanes

`FeedScrollBenchmark` flings the host's `FeedActivity` (200 cards, alternating `banner-classic` and `poll-card`) and
`PagingActivity` (an endless androidx Paging feed, pages of 20 taking 300 ms). Extras on `FeedActivity` set the lane:

- `ahead` (`-1`: slots only; `n`: `LiveryPrepareAhead` with `n` cards ahead) and `planDelayMs` (what planning one card costs the
  app: 20 ms unless a lane says 60): the lazy lanes prepare nothing before the list shows.
- `animateMs` (the visible polls' numbers change at that period), `eased` (the catalog built with `--default-animations`, so a
  template's bars ease), and `twinEases` (false: the Compose UI twin's bars land at once). The pairs: `composeUiAnimatedNoEase` with
  `liveryAnimated`, `composeUiAnimated` with `liveryAnimatedEased`.
- `host:placeholder` slices count the placeholders that became visible: cards that reached the screen unprepared.

## What they show

- **A lazy feed stays level when it prepares ahead.** Slots alone leave unprepared cards on the screen (11 with 20 ms planning, 61 with
  60 ms) and a p99 of 11 to 13 ms; with `LiveryPrepareAhead` and 2, 4, 8 or 16 cards the count is 0 and the p99 is 7.3 to 9.1 ms. The
  window has to cover the planning time of the fastest fling (about 6 cards a second here); the default is 8, which holds at 60 ms.
- **An ordinary frame of the animated feed is as cheap as the twin's, and one frame a second is not.** Livery's p50 and p90 are at or
  under the twin's. Its p99 is 18 ms against 12: the frame on which every visible card's new values compose. In the trace, 13 frames of
  a run are over 12 ms (the longest 20 ms), each with about 10 ms of `Recomposer:recompose` (43 composition passes in all) and about
  6 ms of drawing (`draw-VRI`); the twin has none over 12 ms.
- **A card updates in place.** A slot with new data for the same item keeps its card on screen while the new answer plans, with its
  player's state; another item starts from the placeholder with a fresh player. Both lanes draw 0 visible placeholders in the animated
  feed.

## Open

The frame on which every visible card updates, about 10 ms of composition across the cards plus about 6 ms of recording their draw.
Spreading a batch of updates over frames, or not re-recording the draw of a card whose update only moves a bar, are the candidates; the
twin's worst frames stay at 12 ms. The trace queries: the main thread is the thread whose `tid = pid`; take the `animation` slices over
3 ms and total their descendants by name (the `perfetto-traces` skill has the rest).
