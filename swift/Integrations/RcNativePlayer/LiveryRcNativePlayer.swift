// Add this file to an iOS app target that already depends on rc-players' RcNativePlayerUIKit
// (the self-contained source package attached to each rc-players release, as
// homeassistant-remotecompose vendors it in ios/Packages). It is not part of the Livery package so
// that depending on Livery never pulls in a player, or the Compose XCFramework rc-players' root
// package declares.
//
//   LiverySlotView(key: data, actions: router, events: analytics, plan: { await planner.forAppData(query, data: data) },
//                  player: { LiveryRcNativePlayer(input: $0) }, fallback: { _ in NativeTile() })

import Livery
import LiveryUI
import RcNativePlayerCore
import RcNativePlayerUIKit
import SwiftUI

public struct LiveryRcNativePlayer: UIViewRepresentable {
  public let input: PlayerInput
  public init(input: PlayerInput) { self.input = input }

  public func makeCoordinator() -> Coordinator { Coordinator() }

  public func makeUIView(context: Context) -> RemoteComposeNativePlayerView {
    let view = RemoteComposeNativePlayerView(
      data: input.document, background: .transparent, compatibilityPolicy: .compatible, androidCompatibility: .enabled,
      customComponents: nil, onEvent: { event in handle(event) }, onDiagnostics: { _ in })
    context.coordinator.loaded = input.document
    context.coordinator.apply(input.values, to: view)
    return view
  }

  public func updateUIView(_ view: RemoteComposeNativePlayerView, context: Context) {
    view.onEvent = { event in handle(event) }
    if context.coordinator.loaded != input.document {
      view.load(input.document)
      context.coordinator.loaded = input.document
      context.coordinator.pushed = [:]
    }
    context.coordinator.apply(input.values, to: view)
  }

  private func handle(_ event: RemoteComposeNativePlayerEvent) {
    // PROTOCOL.md §7: nothing, a text value (JSON arguments or one value), or a number (as `value`).
    guard case .namedAction(let name, let value) = event else { return }
    switch value {
    case .none: input.onAction(name, nil)
    case .text(let text): input.onAction(name, text)
    case .integer(let i): input.onAction(name, String(i))
    case .float(let f): input.onAction(name, String(f))
    case .floatList: return // not an action payload Livery defines
    }
  }

  @MainActor public final class Coordinator {
    var loaded: Data?
    var pushed: [String: PlayerValue] = [:]

    /// Pushes only what changed, so a live update animates and nothing else restarts.
    func apply(_ values: [String: PlayerValue], to view: RemoteComposeNativePlayerView) {
      let changed = values.filter { pushed[$0.key] != $0.value }
      pushed = values
      Task { @MainActor in
        for (name, v) in changed {
          switch v {
          case .text(let s): _ = await view.setString(s, for: name)
          case .integer(let i): _ = await view.setInteger(i, for: name)
          case .float(let f): _ = await view.setFloat(f, for: name)
          case .color(let c): _ = await view.setColor(c, for: name)
          }
        }
      }
    }
  }
}
