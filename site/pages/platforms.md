title: Platforms
order: 24

# Platforms

| Module | Platforms | Status |
| --- | --- | --- |
| `livery-protocol`, `livery-client` | Android, JVM, iOS, Wasm | Available |
| `livery-compose` | Android, desktop, iOS, Wasm (Compose Multiplatform) | Available |
| `livery-bom` (a `platform(…)` with every module's version) | Gradle and Maven | From the next release |
| `livery-compose-material3` (`ColorScheme.toLiveryTheme()`, and its inverse `ColorScheme.withLiveryTheme(pack)`) | Android, desktop, iOS, Wasm (Compose Multiplatform) | Available from the release after 0.4.0; `withLiveryTheme` from the next release |
| `livery-instantiate` | Android, JVM, iOS, Wasm | Available |
| `livery-launcher-widgets` | Android 16+ | Available; not yet device-tested in CI |
| `livery-verify` | Robolectric (Android 16, Java 21) | Available: launcher widgets on Android 16's own player |
| `Livery`, `LiveryUI` (Swift package) | iOS 15, macOS 12, watchOS 8, tvOS 15 | Available; CI on macOS |
| `livery-catalog` (catalog build and check, on any Okio file system) | JVM, iOS, Wasm | Available from the release after 0.4.0 |
| `livery-publish` (`livery` CLI) | JVM | Available |
| `livery-gradle-plugin` (`ee.schimke.composeai.livery.catalog`) | Gradle, Android apps | Available |
| `livery-wear-widgets` | Wear OS (Glance Wear) | Available; not yet device-tested in CI |
| `livery-notifications` | Android 16+ | Available; not yet device-tested in CI |
| `livery-firebase` | Android | Available (Firebase Analytics, Remote Config, Firestore); not yet tested against a live project |
| `@yschimke/livery-a2ui` (`web/a2ui`): Livery components in A2UI's Lit, React and Angular renderers, over `livery-web` (Wasm) | Browsers | Built; not yet on npm |
| `@yschimke/livery-web` (a `<livery-template>` element outside A2UI) | Browsers | Planned |
| `livery-render` | JVM (Linux, macOS, Windows) | Available |
| `livery-mcp-core` (the agent tools, transport-free) | JVM, iOS, Wasm | Available from the release after 0.4.0 |
| `livery-mcp` (`livery-mcp` CLI) | JVM | Available; live sessions planned |
| `livery-sessions-core` (agent sessions, transport-free: relay, links, conversation, steps) | JVM, iOS, Wasm | Available from 0.21.0 |
| `livery-sessions-server` (agent sessions over HTTP: routes, MCP tools, `SessionKind`, save file) | JVM (Ktor) | Available from the next release; the client and the Compose panel *Planned* |
| `livery-sessions-templates` (`TemplateKind`: agents draft templates for a contract, held to the gate for unsigned documents) | JVM | Available from the next release |
| `livery-datadog`, `livery-sentry`, `livery-amplitude` | Android | Available since 0.2.0; not yet tested against live projects |
| `livery-capture-cmp` (authoring on rc-players' Compose Multiplatform creation API) | JVM, Android; compiled for Wasm, not yet tested in a browser | Available from the release after 0.9.0 |
| `livery-capture` (authoring on AndroidX `remote-creation-compose`) | Android (unit tests, Robolectric) | Available since 0.2.0 |
| `livery-capture-core` (what both share: `CapturedTemplate`, `DesignFolder`, `CatalogContracts`) | JVM (also what Android resolves); compiled for Wasm | Available from the release after 0.9.0 |
| Secondary screens (`LiveryScreens`, `LiveryScreenHost`, `.liveryScreens`) | Kotlin, Compose, SwiftUI | Available from the release after 0.2.0; not yet device-tested |
| `livery-uibuilder` | JVM, iOS, Wasm (the CLI commands on the JVM) | Available (optional: `livery ui-builder` commands and `livery-mcp` tools) |
| `livery-uibuilder-capture` (`.uid` documents captured at build time) | JVM | Available from the release after 0.22.0 (optional: the Gradle plugin's `capture { uiBuilder() }`) |
| `livery-kobento` | JVM | Available (optional: a library, `livery kobento` commands and `livery-mcp --kobento` tools); device push and a Kobento review lane *Planned* |
| `livery-server` | JVM | Planned |

## Kotlin

- HTTP through Ktor: OkHttp on Android and the JVM, Darwin on iOS, `fetch` on Wasm.
- `JavaCrypto` (JVM, Android 13+) verifies Ed25519; on older Android or other targets pass your
  own `Crypto`.
- Android modules compile against SDK 37.1.

## Compose Multiplatform

`LiveryTemplate(plan, host)` plays a render plan through rc-players' Compose player;
`LiverySlot(key, host) { plan }` plans and falls back, replanning when `key` changes or the
repository's `revision` does (a refreshed catalog, an install). Catalog images and fonts go through the
player's own loaders.

## Swift

A native SwiftPM package backed by `URLSession`, CryptoKit and SwiftUI (`Livery` and `LiveryUI`),
plus two optional products built on the pure-Swift
[`yschimke/rc-swift`](https://github.com/yschimke/rc-swift) mirror (no Compose XCFramework):

- **`LiveryRcNativePlayer`**: SwiftUI player adapter over `RcNativePlayerUIKit` with incremental
  named-value updates, iOS 16+ `sizeThatFits` aspect-ratio sizing, and Livery image/font resolvers
  (`LiveryNativeResourceResolver`, `LiveryNativeFontResolver`). `androidCompatibility` defaults to
  `.disabled` so captured templates scale cleanly to the SwiftUI container width; pass
  `androidCompatibility: .enabled` to opt into Android dp-density scaling.
- **`LiveryCapture`**: author Livery templates in Swift with `RcCreation` (`LiveryValues`,
  `LiveryCapture.capture`, `CatalogSource`), writing standard catalog source trees for
  `livery build` — or build a deterministic offline bundled catalog (`manifest.json` + `blobs/`)
  directly in pure Swift with `CatalogSource.buildBundled(to:)` (no JVM required).
- **`LiveryCatalogPlugin`**: SwiftPM command plugin that runs a project's Swift template generator
  executable (`<source> <out>`) and optionally invokes `livery build --inspect`:

```sh
swift package --allow-writing-to-package-directory livery-build \
  --target CatalogTemplates \
  --source catalog-source \
  --out App/Resources/livery
```

`LiveryHost` wires file stores, `TemplateRepository` (including
`TemplateRepository.Bundled.fromBundle()`), `SlotPlanner` and `.inAppNativeSwift` capabilities:

```swift
LiverySlotView(key: decision.decisionId, actions: router, events: analytics,
  plan: { await host.planner.forDecision(query, decision) },
  player: { LiveryRcNativePlayer(input: $0, host: host) },
  fallback: { _ in NativeBanner() })
```

## Conformance

Kotlin and Swift run the same fixtures in
[`conformance/`](https://github.com/yschimke/livery/tree/main/conformance): selection, binding,
resources and signing must give identical answers on every platform.
