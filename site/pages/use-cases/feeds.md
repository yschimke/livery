title: Feeds
order: 12

# Feeds

**In one line:** a feed page is a list of template references plus data; a handful of installed
templates draw thousands of items, and each page ships data only.

## How it fits

- The feed's **templates** are few, `"install": "Always"`, so they are on disk before the feed
  loads.
- Your **feed API** returns `FeedPage`s: items, each with a template reference and its data.
- An item whose template can't play (missing, too new for this player, bad data) falls back to your
  native row; the feed never shows a hole.

## 1. A feed page

```json
{
  "feed": "home", "next": "c2",
  "items": [
    { "id": "a1", "template": { "designId": "article-card", "contract": "feed/article@1" },
      "data": { "title": "Remote Compose in production", "source": "Engineering",
                "published": 1793000000, "tags": [{ "label": "android" }] } }
  ]
}
```

## 2. Load and draw

```kotlin
val feedClient = FeedClient { feed, cursor -> api.page(feed, cursor) }   // your API

LazyColumn {
  items(page.items, key = { it.id }) { item ->
    // Keyed on the whole item: a refresh that changes an item's data or template replans it.
    LiverySlot(key = item, host = host, slotId = "home-feed", fallback = { NativeArticleRow(item) }) {
      planner.forFeedItem(TemplateQuery("feed/article@1", inApp, widthDp = 360), item)
    }
  }
}
```

Each item is its own measured view: `Impression` and `Viewable` carry the item id.

## 3. Draw every card on its first frame

Every card of a template shares that template's prepared document (decoded, linked and laid out once), fonts and images, kept in memory
by the host (`host.resources`, a `LiveryResources`). So only a template's very first card does any
work before it can draw; every later card draws complete on the frame it first appears, with its own
data and no animation from another card's values. Share one `LiveryHost` across the feed.

With a `slotId` (a name for the slot on this host), a slot also remembers its answer per key, so an
item scrolled away and back shows its card at once instead of the fallback. When a page arrives,
prepare its items before they scroll into view. `prepareSlot` makes the same planning call as the
slot, for the same `slotId` and key, keeps the answer and loads the item's document, fonts
and images, so even an item's first frame shows the finished card rather than the fallback:

```kotlin
// When a page arrives, before it scrolls into view:
coroutineScope {
  page.items.forEach { item ->
    launch { host.resources.prepareSlot("home-feed", item) { planner.forFeedItem(query, item) } }
  }
}
```

`host.resources.preload(plans)` does the loading half for plans you already have, and
`host.resources.warm(queries)` gets the templates a screen's queries choose ready before you have
any data for them (when a catalog loads, say). Every cache is bounded and drops its oldest entries
first; a dropped entry only costs the next card that work again.

### Nothing is decoded while the feed scrolls

A template is decoded, linked and laid out off the main thread. The host configures the player's
background preparation (`LiveryHost.preparation`, by default `RcPreparation.Background` over
`host.resources.documents`): composition only looks a template up, and a slot swaps a new answer in
only once its template is prepared and its fonts loaded. A feed that scrolls never prepares a
template on its main thread, whether or not you prepared anything; preparing ahead only decides
whether a template's first card shows on its first frame. `host.resources.misses` counts
the cards that had to wait, which a test can hold at zero. A template whose bytes the player cannot
play (`host.resources.cannotPlay`) never fails the app: warming or preloading skips it, a
`LiverySlot` draws its `fallback` instead, and a bare `LiveryTemplate` draws nothing. Pass
`preparation = RcPreparation.InComposition` to prepare a template's first card in its composition
instead.

Preparing also rewrites the template for the display it is drawn on (`LiveryResources.ForDisplay`,
rc-players' `forDisplay`): the density and font scale are hardcoded, values no card binds are frozen,
and the expressions that leaves are folded, once per template and display, so every card evaluates
fewer expressions. A slot records its display on the host (`host.resources.display`); to warm before
anything has composed, pass one: `host.resources.warm(queries, rememberLiveryDisplay())`. Pass
`preprocessor = null` to `LiveryResources` to play templates exactly as published.

While a list scrolls, the work that remains is per card on screen. Two host settings cut it:

- `LiveryHost.deferLayoutAnimations` (on by default): a template that can only change through its
  data skips the lookahead pass behind its bounds animations until its data first changes, and then
  animates as before. That pass otherwise runs over every card on every frame a list scrolls. A
  template that animates, reads the clock, scrolls, takes input or writes its own state keeps the
  pass from its first frame. Pass `false` to play every template exactly as AndroidX does.
- `LiveryHost.flattenLayout` (on by default): a template's plain boxes, rows, columns and texts are
  laid out and drawn by their container in one node, rather than one composable each. Tap targets,
  data-driven badges and custom components stay components of their own, the texts stay in the
  semantics tree, and anything the flat layout cannot draw exactly as the components would stays on
  the per-component path. It renders the same pixels, and applies only while layout animations are
  deferred. Pass `false` to give every component its own composable.

Only the document is prepared in the background. Each card is still ordinary Compose: its own nodes,
laid out, drawn and in the semantics tree on the main thread, never a picture drawn elsewhere.

## 4. Evolve the layout

Publish a new design for the same contract and point the feed's items at it, or change the design's
template in place: the next refresh downloads it, and items render with it from then on. Old app
versions keep the templates their player can draw.

## Deployment checklist

- [ ] Feed templates `Always`, for every surface the feed appears on.
- [ ] A native fallback row for every contract.
- [ ] The feed API validates item data against the contract.
- [ ] Remote thumbnails through the image proxy.
- [ ] One `LiveryHost` shared by the whole feed, and `prepareSlot` called for each page's items.
- [ ] The feed's templates warmed (`host.resources.warm`) when its catalog loads.
