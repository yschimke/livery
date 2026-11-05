title: Images and fonts
order: 22

# Images and fonts

A template never fetches anything itself; every image and font it draws comes through the client.

## Catalog assets

Put images a design needs in `designs/<id>/assets/` and fonts in `fonts/` with `fonts/fonts.json`:

```json
[ { "file": "Figtree-SemiBold.ttf", "family": "Figtree", "weight": 600, "license": "OFL-1.1" } ]
```

A template lists the families it uses (`"fonts": ["Figtree"]`, or `google:` / `system:` families).
Assets are content-addressed and verified, and download with the template; a template is never
handed out without them. Catalogs with assets require protocol level 2 clients.

## Images in data

| Value | Meaning |
| --- | --- |
| `asset:<sha256>` | a catalog image |
| `https://…` | a remote image, through the image proxy or an allowlisted host |
| `app:<key>` | an image your app supplies (an avatar, an entity picture) |

Anything else is refused by the contract check. A template draws a data-bound image from the URL
`livery:value/<input>`.

## The image proxy

Declare it in `catalog.json`; `livery build` writes it into the manifest:

```json
"imageProxy": {
  "urlTemplate": "https://img.acme.example/{width}x{height}/{format}?u={url}",
  "sourceHosts": ["cdn.adnet.example"], "maxWidthPx": 1600, "maxHeightPx": 1600
}
```

The device then talks only to the proxy: images arrive sized for the slot, in one format, without
cookies or trackers. Without a proxy, only hosts the app allowlists (`ImageResolver(directHosts = …)`)
are fetched. Redirects are checked hop by hop, URLs with user info are refused, and downloads stop at
`maxBytes`.

```kotlin
val images = ImageResolver(repository, KtorFetcher(), JavaCrypto, directHosts = setOf("cdn.acme.example"),
  app = { key, w, h -> avatars.load(key, w, h) })
val host = LiveryHost(repository, actions, events, clock, images = images)
```
