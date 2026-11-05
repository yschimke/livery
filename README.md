# Livery

Server-driven [Remote Compose](https://developer.android.com/jetpack/androidx/releases/compose-remote)
templates for mobile, widget, watch and web apps. Publish a design once; every app screen, launcher
widget, Wear widget and web page that plays it takes it on, without an app release.

Livery is the plumbing between "a `.rc` document exists" and "it is on screen with the right data":

- **Fetch, verify and cache** templates from any host: a static site, a **GitHub release**, a
  CDN, a design server, or app assets. Signed manifests (Ed25519, per-publisher keys that can be
  limited to some contracts), content-addressed blobs, rollback protection, offline-first fallback.
- **Pick** the right document for each player: surface, Remote Compose API level, op profile,
  size, theme, Wear container.
- **Fill it with data** through a typed **contract**, by live push (in-app) or by writing values
  into the document (launcher and Wear widgets), with one validation gate everywhere.
- **List, preview and install** designs and themes, including a live preview of one not yet
  downloaded.
- **Decide** what a placement shows (local rules with schedules, segments, rollouts and frequency
  caps, or your own decision server), render **feeds** of data-only items, route **actions**
  through an allowlist, and report **events**.
- **Author with anything** that produces `.rc` bytes, and work with **any AI workflow**: CI on
  pull requests, live co-editing sessions, generated campaign data, runtime personalisation.

The same model serves interactive ads, growth features, feeds and live dynamic content such as
Home Assistant cards.

**Documentation: <https://yschimke.github.io/livery/>**, with a guide per use case from the
one-line idea to a deployment checklist. The protocol is [docs/PROTOCOL.md](docs/PROTOCOL.md);
the design rationale [docs/DESIGN.md](docs/DESIGN.md).

## Status

Early. In this repository today:

| Module | |
| --- | --- |
| `livery-protocol` | Kotlin Multiplatform wire types: manifest, contracts, designs, templates, targets, resources, themes, placements, decisions, feeds, events. Normative spec: [docs/PROTOCOL.md](docs/PROTOCOL.md) |
| `livery-client` | Kotlin Multiplatform client (JVM, Android, iOS, Wasm): sources, signing and trust, cache, selection, install, preview, images and fonts, decisions, feeds, actions, events; HTTP on Ktor (OkHttp, Darwin, `fetch`) |
| `livery-compose` | Compose Multiplatform: `LiveryTemplate`, `LiverySlot`, design pickers, over rc-players' Compose player |
| `Livery`, `LiveryUI` (Swift) | native SwiftPM package (`Package.swift` at the root): `URLSession`, CryptoKit, SwiftUI views over any player; an adapter for rc-players' native Swift player |
| `livery-instantiate` | writes a slot's values, images and numeric taps into a template for hosts that only play bytes (launcher and Wear widgets, snapshots), on rc-players' `rc-widget-core` |
| `livery-launcher-widgets` | `LiveryLauncherWidgetProvider`: launcher AppWidgets (Android 16+) from Livery templates, on rc-players' `rc-widget-launcher` |
| `livery-wear-widgets` | `LiveryWearWidget`: Glance Wear widgets from Livery templates, framed per container on rc-players' `rc-widget-core` |
| `livery-publish` | `livery` CLI: validate a source tree (`--inspect` reads every document), write a static or GitHub-release catalog, sign, verify |
| `livery-render` | headless PNG renders of templates on the JVM, through rc-players' Compose player |
| `livery-mcp` | `livery-mcp` CLI: agent tools (MCP over stdio) over a catalog source tree: contracts, data and template validation, previews, drafts, one-document previews, version history |
| `livery-uibuilder` | optional: Compose UI Builder designs (at a revision) as documents for `livery-mcp` |
| `livery-gradle-plugin` | `ee.schimke.composeai.livery.catalog`: builds a catalog into an Android app's assets, read back with `AndroidBundled.fromAssets` |
| `conformance/` | fixtures every client must reproduce: selection, binding, resources, signing |
| `samples/catalog` | a promo banner, a feed article and a live tile, with launcher and Wear widget targets, an image and a font. `tile-default/in-app.rc` is a real document that renders; the other `.rc` and font files are placeholders |

Planned: the Android View player, phone-to-watch template sync, a web client, and an optional
server (with an image proxy and live agent sessions). See the
[roadmap](docs/DESIGN.md#roadmap).

## Try it

```sh
./gradlew build :livery-publish:installDist
alias livery=livery-publish/build/install/livery/bin/livery

livery keygen sample                                   # sample.key (secret), sample.pub (embed in apps)
livery build samples/catalog build/catalog --key sample.key --key-id sample-1
livery verify build/catalog --pub sample.pub --key-id sample-1

# As GitHub release assets:
livery build samples/catalog build/release --github <owner>/<repo> --tag catalog-1 --key sample.key --key-id sample-1
gh release create catalog-1 build/release/*
```

For agents (MCP over stdio): `./gradlew :livery-mcp:installDist`, then
`livery-mcp/build/install/livery-mcp/bin/livery-mcp samples/catalog`. Try `render_preview` with
`design=tile-default`, `template=in-app`, `example=on`. See
[AI workflows](site/pages/ai-workflows.md).

In an app:

```kotlin
val repo = TemplateRepository(
  source = GitHubReleaseSource("acme", "app-templates"),
  fetcher = KtorFetcher(), crypto = JavaCrypto,             // Ktor: OkHttp on JVM/Android, Darwin on iOS
  trust = TrustPolicy.Signed(listOf(TrustedKey("acme-1", publicKeyBytes))),
  blobs = FileBlobStore(cacheDir.resolve("livery/blobs")),
  kv = FileKeyValueStore(filesDir.resolve("livery")),
  clock = { System.currentTimeMillis() },
  targets = listOf(inAppPlayer, launcherWidgetPlayer),
)
repo.refresh()                                         // from your background scheduler
val plan = SlotPlanner(repo, analytics, clock).forDecision(
  TemplateQuery("promo/banner@1", inAppPlayer, widthDp = 360),
  decider.decide("home.top", context) ?: return,
)
```

On iOS:

```swift
// Package.swift: .package(url: "https://github.com/yschimke/livery", branch: "main")
let repo = TemplateRepository(
  source: GitHubReleaseSource(owner: "acme", repo: "app-templates"),   // URLSession
  trust: .signed([TrustedKey(keyId: "acme-1", publicKey: publicKey)]),  // CryptoKit Ed25519
  blobs: FileBlobStore(directory: caches.appendingPathComponent("livery")),
  kv: FileKeyValueStore(directory: support.appendingPathComponent("livery")),
  targets: [PlayerCapabilities(surface: Surfaces.inApp, maxRcApiLevel: 7, profiles: ["core"])])
```

## License

Apache 2.0.
