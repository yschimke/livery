title: Platforms
order: 24

# Platforms

| Module | Platforms | Status |
| --- | --- | --- |
| `livery-protocol`, `livery-client` | Android, JVM, iOS, Wasm | Available |
| `livery-compose` | Android, desktop, iOS, Wasm (Compose Multiplatform) | Available |
| `livery-instantiate` | Android, JVM, iOS, Wasm | Available |
| `livery-launcher-widgets` | Android 16+ | Available; not yet device-tested in CI |
| `Livery`, `LiveryUI` (Swift package) | iOS 15, macOS 12, watchOS 8, tvOS 15 | Available; CI on macOS |
| `livery-publish` (`livery` CLI) | JVM | Available |
| `livery-gradle-plugin` (`ee.schimke.livery.catalog`) | Gradle, Android apps | Available |
| `livery-wear-widgets` | Wear OS (Glance Wear) | Available; not yet device-tested in CI |
| `@livery/web` | Browsers | Planned |
| `livery-render` | JVM (Linux, macOS, Windows) | Available |
| `livery-mcp` (`livery-mcp` CLI) | JVM | Available; live sessions planned |
| `livery-uibuilder` | JVM | Available (optional `livery-mcp` extension) |
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
