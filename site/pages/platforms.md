title: Platforms
order: 24

# Platforms

| Module | Platforms | Status |
| --- | --- | --- |
| `livery-protocol`, `livery-client` | Android, JVM, iOS, Wasm | Available |
| `livery-compose` | Android, desktop, iOS, Wasm (Compose Multiplatform) | Available |
| `livery-bom` (a `platform(…)` with every module's version) | Gradle and Maven | From the next release |
| `livery-compose-material3` (`ColorScheme.toLiveryTheme()`) | Android, desktop, iOS, Wasm (Compose Multiplatform) | Available from the release after 0.4.0 |
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
| `livery-datadog`, `livery-sentry`, `livery-amplitude` | Android | Available since 0.2.0; not yet tested against live projects |
| `livery-capture-cmp` (authoring on rc-players' Compose Multiplatform creation API) | JVM, Android; compiled for Wasm, not yet tested in a browser | Available from the release after 0.9.0 |
| `livery-capture` (authoring on AndroidX `remote-creation-compose`) | Android (unit tests, Robolectric) | Available since 0.2.0 |
| `livery-capture-core` (what both share: `CapturedTemplate`, `DesignFolder`, `CatalogContracts`) | JVM (also what Android resolves); compiled for Wasm | Available from the release after 0.9.0 |
| Secondary screens (`LiveryScreens`, `LiveryScreenHost`, `.liveryScreens`) | Kotlin, Compose, SwiftUI | Available from the release after 0.2.0; not yet device-tested |
| `livery-uibuilder` | JVM, iOS, Wasm (the CLI commands on the JVM) | Available (optional: `livery ui-builder` commands and `livery-mcp` tools) |
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

A native SwiftPM package with no dependencies beyond the system: `URLSession`, CryptoKit, SwiftUI.
`LiverySlotView` takes any player view. For rc-players' native Swift player, add
[`swift/Integrations/RcNativePlayer/LiveryRcNativePlayer.swift`](https://github.com/yschimke/livery/blob/main/swift/Integrations/RcNativePlayer/LiveryRcNativePlayer.swift)
to your app beside your own `RcNativePlayerUIKit` dependency.

```swift
LiverySlotView(key: decision.decisionId, actions: router, events: analytics,
  plan: { await planner.forDecision(query, decision) },
  player: { LiveryRcNativePlayer(input: $0) },
  fallback: { _ in NativeBanner() })
```

## Conformance

Kotlin and Swift run the same fixtures in
[`conformance/`](https://github.com/yschimke/livery/tree/main/conformance): selection, binding,
resources and signing must give identical answers on every platform.
