title: Livery
order: 0

# Livery

<p class="lede">Server-driven <a href="https://developer.android.com/jetpack/androidx/releases/compose-remote">Remote Compose</a> templates for apps, launcher widgets, watches and the web. Publish a design once; every surface that plays it takes it on, without an app release.</p>

Livery sits between "there is a `.rc` document" and "it is on screen with the right data". It
fetches, verifies and caches templates from any host, picks the right one for each player and size,
fills it with data through a typed contract, and reports what was shown and tapped.

## What you can build

| Use case | In one line |
| --- | --- |
| [Interactive ads](use-cases/ads.md) | Your ad server answers a placement with a template reference and its data; Livery draws exactly that creative, or nothing. |
| [Promotions and growth](use-cases/promotions.md) | Ship rules in the catalog (window, audience, rollout, frequency cap) and the app picks the promotion on the device, offline. |
| [Feeds](use-cases/feeds.md) | A feed page is a list of template references plus data; a handful of templates draw thousands of items. |
| [Live content](use-cases/live-content.md) | The app pushes its own live values into a template, and people install skins for it. |
| [Launcher widgets](use-cases/launcher-widgets.md) | Subclass one provider; Livery instantiates the template for each widget's size and routes taps back. |
| [Wear widgets](use-cases/wear-widgets.md) | Subclass one Glance Wear widget; Livery frames the template in each watch's container and turns taps into your `PendingIntent`s. |
| [Notifications](use-cases/notifications.md) | Render a template for a notification's expanded body; taps come back to your receiver. |
| [The web](use-cases/web.md) | *Planned.* A `<livery-template>` element over the TypeScript player. |

## How it fits together

```
 authoring (any tool)        livery CLI                any host                    your app
 .rc + sidecar JSON   ─────▶ validate, sign  ───────▶  GitHub release / CDN  ───▶  fetch, verify, cache
                             manifest + blobs          design server / assets      select, bind, play
```

- **Contract**: what a template reads and may emit (`promo/banner@1`). The one thing app and
  designer agree on.
- **Design**: one look for a contract, as a picker shows it (`banner-classic`).
- **Template**: one values-free `.rc` per design and target (in-app, launcher widget, Wear, web).
- **Catalog**: a signed `manifest.json` plus content-addressed files, on any static host.

Read [Concepts](concepts.md) for the model, or go straight to [Getting started](getting-started.md).

## Platforms

| | Status |
| --- | --- |
| Kotlin Multiplatform client (Android, JVM, iOS, Wasm) | Available |
| Compose Multiplatform (`LiveryTemplate`, `LiverySlot`) | Available |
| Swift package (`Livery`, `LiveryUI`) | Available; CI on macOS |
| Launcher widgets (Android 16+) | Available |
| Wear widgets (Glance Wear) | Available |
| Agent tools (`livery-mcp`) and headless previews | Available |
| Phone-to-watch sync, web element, reference server | Planned |

See [Platforms](platforms.md) for details.
