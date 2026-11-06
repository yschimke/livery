import Foundation
import Livery
import SwiftUI

#if canImport(UIKit)
  import UIKit
#endif

/// A value as a Remote Compose player takes it. Images are not named values: a document draws a
/// data-bound image from the URL `livery:value/<input>` (PROTOCOL.md §6.1).
public enum PlayerValue: Equatable, Sendable {
  case text(String)
  case integer(Int)
  case float(Float)
  case color(UInt32)
}

extension Bindings {
  /// Booleans become 0/1 integers, as Remote Compose players read them.
  public var playerValues: [String: PlayerValue] {
    var out: [String: PlayerValue] = [:]
    for (name, v) in values {
      switch v {
      case .string(let s): out[name] = .text(s)
      case .int(let i): out[name] = .integer(Int(i))
      case .long(let l): out[name] = .integer(Int(truncatingIfNeeded: l))
      case .float(let f): out[name] = .float(f)
      case .bool(let b): out[name] = .integer(b ? 1 : 0)
      case .color(let argb): out[name] = .color(UInt32(truncatingIfNeeded: argb))
      case .image: break
      }
    }
    return out
  }
}

/// What a player is handed: the document, its values, and where its host actions go.
public struct PlayerInput {
  public let document: Data
  public let values: [String: PlayerValue]
  public let template: LoadedTemplate
  /// Call with a host named action's name and its text value (or nil); PROTOCOL.md §7.
  public let onAction: (String, String?) -> Void
}

/// Plays a `RenderPlan` with any Remote Compose player view (`player`), routing actions through
/// `actions` and reporting Impression on first appearance and Viewable after a second on screen.
public struct LiveryTemplateView<Player: View>: View {
  let plan: RenderPlan
  let actions: ActionRouter
  let events: EventSink
  let clock: @Sendable () -> Int64
  let player: (PlayerInput) -> Player
  @State private var impressed = false
  @State private var viewed = false
  @State private var appeared = false
  @State private var fraction: CGFloat = 0
  private var visible: Bool { appeared && fraction >= 0.5 }

  public init(plan: RenderPlan, actions: ActionRouter, events: @escaping EventSink, clock: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }, @ViewBuilder player: @escaping (PlayerInput) -> Player) {
    self.plan = plan; self.actions = actions; self.events = events; self.clock = clock; self.player = player
  }

  public var body: some View {
    player(PlayerInput(document: plan.template.bytes, values: plan.bindings.playerValues, template: plan.template) { name, value in
      guard let args = ActionRouter.arguments(fromText: value) else { return }
      let plan = plan, actions = actions
      Task { await actions.dispatch(plan, name, args) }
    })
    .background(GeometryReader { g in Color.clear.preference(key: VisibleFractionKey.self, value: visibleFraction(g)) })
    .onPreferenceChange(VisibleFractionKey.self) { fraction = $0 }
    .onAppear {
      appeared = true
      if !impressed { impressed = true; events(event(.impression)) }
    }
    .onDisappear { appeared = false }
    // Viewable (PROTOCOL.md §8): at least half on screen for an unbroken second. The task restarts
    // each time that becomes true and is cancelled when it stops, so a glimpse never counts.
    .task(id: visible) {
      guard visible, !viewed else { return }
      try? await Task.sleep(nanoseconds: 1_000_000_000)
      guard !Task.isCancelled, visible, !viewed else { return }
      viewed = true
      events(event(.viewable))
    }
  }

  private func event(_ type: TemplateEvent.Kind) -> TemplateEvent {
    TemplateEvent(type: type, atEpochMs: clock(), placement: plan.decision?.placement, decisionId: plan.decision?.decisionId,
      feedItemId: plan.feedItem?.id, designId: plan.template.entry.designId, sha256: plan.template.entry.sha256, experiments: plan.experiments)
  }
}

/// The share of a view inside the screen. A clip by an enclosing scroll view that stays on screen
/// isn't seen; on platforms without a screen size it reads fully visible once it appears.
@MainActor
private func visibleFraction(_ g: GeometryProxy) -> CGFloat {
  let frame = g.frame(in: .global)
  let area = frame.width * frame.height
  guard area > 0 else { return 0 }
  #if canImport(UIKit) && !os(watchOS)
    let visible = frame.intersection(UIScreen.main.bounds)
    return visible.isNull ? 0 : (visible.width * visible.height) / area
  #else
    return 1
  #endif
}

private struct VisibleFractionKey: PreferenceKey {
  static let defaultValue: CGFloat = 0
  static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

/// A slot whose content Livery decides: `plan` runs again when `key` changes; `fallback` draws
/// while it runs and when it falls back (the app's native version, or nothing).
public struct LiverySlotView<Player: View, Fallback: View>: View {
  let key: AnyHashable
  let plan: () async -> PlanResult
  let actions: ActionRouter
  let events: EventSink
  let player: (PlayerInput) -> Player
  let fallback: (String?) -> Fallback
  @State private var result: PlanResult?

  public init(key: AnyHashable, actions: ActionRouter, events: @escaping EventSink, plan: @escaping () async -> PlanResult,
    @ViewBuilder player: @escaping (PlayerInput) -> Player, @ViewBuilder fallback: @escaping (String?) -> Fallback) {
    self.key = key; self.plan = plan; self.actions = actions; self.events = events; self.player = player; self.fallback = fallback
  }

  public var body: some View {
    Group {
      switch result {
      // One identity per rendered item: a new decision or feed item in the slot is a new view,
      // with its own impression and viewability (PROTOCOL.md §8), never the previous item's state.
      case .ready(let p)?:
        LiveryTemplateView(plan: p, actions: actions, events: events, player: player)
          .id(p.renderIdentity)
      case .fallback(let reason)?: fallback(reason)
      case nil: fallback(nil)
      }
    }
    .task(id: key) { result = await plan() }
  }
}
