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
    LiverySlot(key = item.id, host = host, fallback = { NativeArticleRow(item) }) {
      planner.forFeedItem(TemplateQuery("feed/article@1", inApp, widthDp = 360), item)
    }
  }
}
```

Each item is its own measured view: `Impression` and `Viewable` carry the item id.

## 3. Evolve the layout

Publish a new design for the same contract and point the feed's items at it, or change the design's
template in place: the next refresh downloads it, and items render with it from then on. Old app
versions keep the templates their player can draw.

## Deployment checklist

- [ ] Feed templates `Always`, for every surface the feed appears on.
- [ ] A native fallback row for every contract.
- [ ] The feed API validates item data against the contract.
- [ ] Remote thumbnails through the image proxy.
