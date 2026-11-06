import Foundation
import Livery
import SwiftUI

/// `LiveryScreens`' stack for SwiftUI.
@MainActor
public final class LiveryScreensModel: ObservableObject {
  public let screens: LiveryScreens
  @Published public private(set) var stack: [PresentedScreen] = []

  public init(_ screens: LiveryScreens) {
    self.screens = screens
    // Each change reads the stack as it is when it runs on the main actor, never the snapshot the
    // change carried: notifications that arrive out of order can't bring back a closed screen.
    screens.onChange = { [weak self, screens] _ in Task { @MainActor in self?.stack = screens.stack } }
  }
}

extension View {
  /// Presents `model`'s secondary screens (§8.2) over this view: a dialog over a scrim, a sheet,
  /// or a full-screen cover (a sheet where the platform has none), each played by `player`, with
  /// the safe-area insets pushed as `livery.inset.*`. A screen may open one more, presented from it.
  /// Swiping a sheet away, or tapping a dialog's scrim, closes it with reason `system`.
  public func liveryScreens<Player: View>(
    _ model: LiveryScreensModel, actions: ActionRouter, events: @escaping EventSink,
    @ViewBuilder player: @escaping (PlayerInput) -> Player
  ) -> some View {
    modifier(ScreenLayer(model: model, index: 0, actions: actions, events: events, player: player))
  }
}

private struct ScreenLayer<Player: View>: ViewModifier {
  @ObservedObject var model: LiveryScreensModel
  let index: Int
  let actions: ActionRouter
  let events: EventSink
  let player: (PlayerInput) -> Player

  private var screen: PresentedScreen? { model.stack.indices.contains(index) ? model.stack[index] : nil }

  private func shown(_ p: Presentation) -> Binding<Bool> {
    Binding(
      get: { screen?.presentation == p },
      set: { if !$0, let s = screen, s.presentation == p { model.screens.dismiss(s, reason: "system") } })
  }

  func body(content: Content) -> some View {
    content
      .overlay { if let s = screen, s.presentation == .dialog { dialog(s) } }
      .sheet(isPresented: shown(.sheet)) { if let s = screen { sheet(s) } }
      #if os(iOS) || os(tvOS) || os(watchOS)
        .fullScreenCover(isPresented: shown(.fullscreen)) { if let s = screen { full(s) } }
      #else
        .sheet(isPresented: shown(.fullscreen)) { if let s = screen { full(s) } }
      #endif
  }

  private func template(_ s: PresentedScreen, _ extra: Bindings = Bindings()) -> some View {
    LiveryTemplateView(plan: s.plan, actions: actions, events: events, extra: extra, player: player)
      .id(s.id)
      .modifier(ScreenLayer(model: model, index: index + 1, actions: actions, events: events, player: player))
  }

  private func dialog(_ s: PresentedScreen) -> some View {
    ZStack {
      Color.black.opacity(0.4).ignoresSafeArea().onTapGesture { model.screens.dismiss(s, reason: "system") }
      template(s)
    }
  }

  @ViewBuilder private func sheet(_ s: PresentedScreen) -> some View {
    if #available(iOS 16, macOS 13, watchOS 9, tvOS 16, *) {
      template(s).presentationDetents([.medium, .large])
    } else {
      template(s)
    }
  }

  private func full(_ s: PresentedScreen) -> some View {
    GeometryReader { g in
      template(s, Bindings([
        ScreenValues.insetTop: .float(Float(g.safeAreaInsets.top)),
        ScreenValues.insetBottom: .float(Float(g.safeAreaInsets.bottom)),
        ScreenValues.insetStart: .float(Float(g.safeAreaInsets.leading)),
        ScreenValues.insetEnd: .float(Float(g.safeAreaInsets.trailing)),
      ]))
      .ignoresSafeArea()
    }
  }
}
