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
| `livery-protocol` | Kotlin Multiplatform wire types: manifest, contracts, designs, templates, targets, resources, themes, placements, decisions, feeds, events; `ContractDef` declares a contract in Kotlin with typed handles, and `ThemeRoles` names the theme roles. Normative spec: [docs/PROTOCOL.md](docs/PROTOCOL.md) |
| `livery-client` | Kotlin Multiplatform client (JVM, Android, iOS, Wasm): sources, signing and trust, cache, selection, install, preview, images and fonts, decisions, feeds, actions, events; HTTP on Ktor (OkHttp, Darwin, `fetch`) |
| `livery-compose` | Compose Multiplatform: `LiveryTemplate`, `LiverySlot`, design pickers, over rc-players' Compose player; `LiveryScreenHost` draws secondary screens (dialogs, sheets, full screens, interstitials) |
| `livery-bom` | the BOM: import it as a `platform(…)` and name Livery modules without versions; a release publishes only the modules that changed, and the BOM pins each at its published version |
| `livery-compose-material3` | optional: `ColorScheme.toLiveryTheme()`, a Material 3 scheme (dynamic colour included) as the `theme.*` roles a template reads |
| `Livery`, `LiveryUI` (Swift) | native SwiftPM package (`Package.swift` at the root): `URLSession`, CryptoKit, SwiftUI views over any player; an adapter for rc-players' native Swift player |
| `livery-instantiate` | writes a slot's values, images and numeric taps into a template for hosts that only play bytes (launcher and Wear widgets, snapshots), on rc-players' `rc-widget-core` |
| `livery-launcher-widgets` | `LiveryLauncherWidgetProvider`: launcher AppWidgets (Android 16+) from Livery templates, on rc-players' `rc-widget-launcher` |
| `livery-wear-widgets` | `LiveryWearWidget`: Glance Wear widgets from Livery templates, framed per container on rc-players' `rc-widget-core` |
| `livery-firebase` | Firebase for analytics (`FirebaseAnalyticsSink`), A/B test arms (`RemoteConfigAssigner`) and frequency caps across devices (`FirestoreFrequencyBackend`); any other pipeline plugs into the same interfaces in `livery-client` |
| `livery-datadog` | `DatadogRumEventSink`: template taps as Datadog RUM `TAP` actions, failures as RUM errors |
| `livery-sentry` | `SentryBreadcrumbEventSink`: Livery events as Sentry breadcrumbs, and Sentry's tag on each slot |
| `livery-amplitude` | `AmplitudeAnalyticsSink`: Livery events in Amplitude, arms as user properties |
| `livery-notifications` | `LiveryNotifications`: notification bodies (Android 16+) from Livery templates, with taps through `LiveryNotificationReceiver` |
| `livery-catalog` | the catalog build and checks `livery` runs, in common Kotlin over any Okio file system (on disk, or a `FakeFileSystem` in a browser, an IDE plugin or an agent): `CatalogBuilder`, `CompatibilityCheck.compare`, kept examples, previews, imports |
| `livery-publish` | `livery` CLI: validate a source tree (`--inspect` reads every document), write a static or GitHub-release catalog, sign, verify, `check` a change against what is published (`--markdown` for a pull request; `review` adds before and after pictures), `play` a template as that release plays it (Compose, or Android 16's launcher widget player), and `import` a document from any authoring tool. Repository mode: reusable `catalog-review` / `catalog-publish` workflows run a catalog from a GitHub repository alone |
| `livery-render` | headless PNG renders of templates on the JVM, through rc-players' Compose player; `CatalogPreviews`, every template in a catalog with each example in each mode (the plugin's `renderLiveryPreviews`) |
| `livery-mcp-core` | the agent tools as a transport-free MCP server in common Kotlin, over any Okio file system; renders, git history and revision checks come from an `McpHost` |
| `livery-mcp` | `livery-mcp` CLI: agent tools (MCP over stdio) over a catalog source tree: contracts, data and template validation, previews, drafts, one-document previews, version history |
| `livery-capture-cmp` | author templates in Compose with rc-players' Compose Multiplatform creation API: contract-typed named values (`LiveryValues`) and capture per target from a plain JVM test, a server or a browser, with no Android, Robolectric or AndroidX alpha |
| `livery-capture` | the same on AndroidX's `remote-creation-compose`, from a Robolectric test: for templates composed with AndroidX-only component libraries. Its `LiveryValues` is `livery-capture-cmp`'s, generated against AndroidX's types |
| `livery-capture-core` | what both share, with no creation API: `ContractChecks`, `CapturedTemplate` (the sidecar read from the document), `DesignFolder`, `CatalogContracts` |
| `livery-web-core` / `web/core` | experimental: standalone Kotlin/Wasm component-preview pipeline and browser player, with a runnable local quantity-stepper demo; see [web preview](web/core/README.md) |
| `livery-uibuilder` | optional: Compose UI Builder as an authoring tool: `livery ui-builder scaffold` / `import`, and `ui_builder_*` tools for `livery-mcp` |
| `livery-review` | `livery review`: the pull request report with each changed design drawn before and after with its contract's examples (a CLI extension that brings `livery-render`) |
| `livery-verify` | tests: plays every launcher widget template, with each contract example, on Android 16's own widget player under Robolectric, and fails on what a home screen would show as an error |
| `livery-gradle-plugin` | `ee.schimke.composeai.livery.catalog`: builds a catalog into an Android app's assets, read back with `AndroidBundled.fromAssets` |
| `conformance/` | fixtures every client must reproduce: selection, binding, resources, signing |
| `samples/catalog` | a promo banner, a feed article, a poll and a live tile, with launcher and Wear widget targets, an image and a font. `banner-classic` and `poll-card` are Kotlin (`livery-capture-cmp`'s `samples/SampleDesigns.kt`), captured at build time by `./gradlew :livery-capture-cmp:sampleCatalogs` into `livery-capture-cmp/build/samples/catalog`, never committed; `tile-default/in-app.rc` is a committed real document; the other `.rc` and font files are placeholders |

Planned: the Android View player, phone-to-watch template sync, a web client, and an optional
server (with an image proxy and live agent sessions). See the
[roadmap](docs/DESIGN.md#roadmap).

## Try it

```sh
./gradlew build :livery-publish:installDist
alias livery=livery-publish/build/install/livery/bin/livery
samples=livery-capture-cmp/build/samples/catalog            # built by `build` (:livery-capture-cmp:sampleCatalogs)

livery keygen sample                                   # sample.key (secret), sample.pub (embed in apps)
livery build $samples build/catalog --key sample.key --key-id sample-1
livery verify build/catalog --pub sample.pub --key-id sample-1

# As GitHub release assets:
livery build $samples build/release --github <owner>/<repo> --tag catalog-1 --key sample.key --key-id sample-1
gh release create catalog-1 build/release/*
```

For agents (MCP over stdio): `./gradlew :livery-mcp:installDist :livery-capture-cmp:sampleCatalogs`, then
`livery-mcp/build/install/livery-mcp/bin/livery-mcp livery-capture-cmp/build/samples/catalog`. Try `render_preview` with
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
