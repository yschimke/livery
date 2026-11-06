import Foundation

/// A placement the app shows as a secondary screen (§8.2): its contract, the presentations it
/// allows (never `.inline`), and the one used when a decision asks for none.
public struct ScreenPlacement: Sendable, Equatable {
  public var placement: String
  public var contract: String
  public var allows: Set<Presentation>
  public var defaultPresentation: Presentation

  public init(placement: String, contract: String, allows: Set<Presentation>, default defaultPresentation: Presentation? = nil) {
    precondition(!allows.isEmpty && !allows.contains(.inline), "\(placement): a screen placement allows dialog, sheet or fullscreen")
    self.placement = placement; self.contract = contract; self.allows = allows
    self.defaultPresentation = defaultPresentation ?? Presentation.allCases.first { allows.contains($0) }!
    precondition(allows.contains(self.defaultPresentation), "\(placement): the default isn't allowed")
  }
}

/// The one gate for screens (§8.2), the Swift counterpart of the Kotlin `ScreenRules`, pinned by
/// `conformance/screens.json`.
public enum ScreenRules {
  /// A screen opened from a slot or by the app is level 1; it may open one more (level 2).
  public static let maxDepth = 2

  public struct Outcome: Equatable, Sendable {
    public var presentation: Presentation?
    public var problem: String?
  }

  public static func presentation(_ placement: ScreenPlacement?, name: String, requested: Presentation, depth: Int) -> Outcome {
    guard let placement else { return Outcome(presentation: nil, problem: "\(name) isn't a screen placement") }
    if depth > maxDepth { return Outcome(presentation: nil, problem: "a screen opened from a screen can't open another") }
    if requested == .inline { return Outcome(presentation: placement.defaultPresentation, problem: nil) }
    if !placement.allows.contains(requested) { return Outcome(presentation: nil, problem: "\(requested.rawValue) isn't allowed for \(name)") }
    return Outcome(presentation: requested, problem: nil)
  }
}

/// A screen on show: what it draws, how, and how deep.
public struct PresentedScreen: Identifiable, Sendable {
  public let id: Int64
  public let placement: String
  public let presentation: Presentation
  public let plan: RenderPlan
  public let depth: Int
}

/// The app's secondary screens (§8.2), the Swift counterpart of the Kotlin `LiveryScreens`:
/// interstitials at the app's own moments (`maybeShow`) and screens a template's `present` action
/// opens (`present`), at most two deep. Opening one never waits on the network (`CacheOnly`), and
/// a screen commits only onto the stack it was planned for (empty, or exactly its parent). Data
/// comes from the opening decision's `screens`, then `appData`, then `decide`. `onChange` hands the
/// stack to the UI (`LiveryScreensModel` in LiveryUI).
public final class LiveryScreens: @unchecked Sendable {
  public typealias Decide = @Sendable (String, DecisionContext) async -> Decision?
  public typealias AppData = @Sendable (String, [String: String]) async -> [String: JSONValue]?

  private let planner: SlotPlanner
  private let decide: Decide
  private let context: @Sendable () async -> DecisionContext
  private let placements: [String: ScreenPlacement]
  private let caps: @Sendable (Presentation) -> PlayerCapabilities
  private let appData: AppData
  private let recordImpression: (@Sendable (Decision, Int64) async -> Void)?
  private let lock = NSLock()
  private var screens: [PresentedScreen] = []
  private var nextId: Int64 = 1
  public var onChange: (@Sendable ([PresentedScreen]) -> Void)?

  public init(planner: SlotPlanner, placements: [ScreenPlacement], caps: @escaping @Sendable (Presentation) -> PlayerCapabilities,
              context: @escaping @Sendable () async -> DecisionContext, decide: @escaping Decide,
              appData: @escaping AppData = { _, _ in nil }, recordImpression: (@Sendable (Decision, Int64) async -> Void)? = nil) {
    self.planner = planner; self.decide = decide; self.context = context; self.caps = caps; self.appData = appData
    self.placements = Dictionary(uniqueKeysWithValues: placements.map { ($0.placement, $0) })
    self.recordImpression = recordImpression
  }

  /// The players screens draw with, one per presentation the placements allow: add them to the
  /// repository's targets, so screen templates are prefetched and can open without the network.
  public static func targets(_ placements: [ScreenPlacement], caps: (Presentation) -> PlayerCapabilities) -> [PlayerCapabilities] {
    Presentation.allCases.filter { p in placements.contains { $0.allows.contains(p) } }.map { p in
      var c = caps(p)
      c.container = p.container
      return c
    }
  }

  /// The screens on show, bottom first.
  public var stack: [PresentedScreen] { lock.lock(); defer { lock.unlock() }; return screens }

  /// Shows `placement` as an interstitial if its rules (or app data) have something for it now and
  /// no screen is showing.
  @discardableResult
  public func maybeShow(_ placement: String) async -> Bool {
    if !stack.isEmpty { return false }
    if let d = await decide(placement, await context()) {
      return await show(placement, d.presentation, depth: 1, decision: d) { q in await self.planner.forDecision(q, d) }
    }
    guard let data = await appData(placement, [:]) else { return false }
    return await show(placement, .inline, depth: 1) { q in await self.planner.forAppData(q, data: data) }
  }

  /// The `present` action handler: opens the screen `action` names, from `from`'s template.
  @discardableResult
  public func present(_ from: RenderPlan, _ action: String, _ args: [String: String]) async -> Bool {
    guard let spec = from.contract.actions[action], spec.kind == ActionKinds.present, let target = spec.placement else {
      return refuse(action, from.decision, "\(action) doesn't name a screen placement")
    }
    let depth = (screen(of: from)?.depth ?? 0) + 1
    if let d = from.decision, let screen = d.screens[action] {
      let decision = Decision(placement: target, decisionId: "\(d.decisionId)>\(action)", template: screen.template, data: screen.data,
        tracking: d.tracking, experiments: d.experiments, presentation: screen.presentation)
      return await show(target, screen.presentation, depth: depth, decision: decision, from: from) { q in await self.planner.forDecision(q, decision) }
    }
    if let data = await appData(target, ActionGate.check(from.contract, action, args).args) {
      return await show(target, .inline, depth: depth, from: from) { q in await self.planner.forAppData(q, data: data) }
    }
    guard let d = await decide(target, await context()) else { return refuse(target, from.decision, "nothing to show for \(target)") }
    return await show(target, d.presentation, depth: depth, decision: d, from: from) { q in await self.planner.forDecision(q, d) }
  }

  /// Closes `screen` and any screen it opened, reporting a `dismiss` with `reason`.
  public func dismiss(_ screen: PresentedScreen, reason: String) {
    lock.lock()
    let i = screens.firstIndex { $0.id == screen.id }
    let closed = i.map { Array(screens[$0...]) } ?? []
    if let i { screens = Array(screens[..<i]) }
    let now = screens
    lock.unlock()
    guard !closed.isEmpty else { return }
    for s in closed.reversed() {
      let p = s.plan
      planner.events(TemplateEvent(type: .dismiss, atEpochMs: planner.clock(), placement: p.decision?.placement ?? s.placement, decisionId: p.decision?.decisionId,
        feedItemId: p.feedItem?.id, designId: p.template.entry.designId, sha256: p.template.entry.sha256, detail: reason, experiments: p.experiments))
    }
    onChange?(now)
  }

  /// The screen `plan` is drawn in, if it is one.
  public func screen(of plan: RenderPlan) -> PresentedScreen? {
    guard let id = plan.screenId else { return nil }
    return stack.first { $0.id == id }
  }

  /// Handlers for an `ActionRouter`: `present`, and `dismiss`, which closes the screen it was tapped
  /// in, or calls `dismissInline` for a slot's template.
  public func handlers(dismissInline: @escaping ActionRouter.Handler = { _, _, _ in }) -> [String: ActionRouter.Handler] {
    [
      ActionKinds.present: { [self] plan, action, args in _ = await present(plan, action, args) },
      ActionKinds.dismiss: { [self] plan, action, args in
        if let s = screen(of: plan) { dismiss(s, reason: "action") } else { await dismissInline(plan, action, args) }
      },
    ]
  }

  private func show(_ placement: String, _ requested: Presentation, depth: Int, decision: Decision? = nil, from: RenderPlan? = nil,
                    plan: @escaping @Sendable (TemplateQuery) async -> PlanResult) async -> Bool {
    let outcome = ScreenRules.presentation(placements[placement], name: placement, requested: requested, depth: depth)
    guard let presentation = outcome.presentation, let spec = placements[placement] else {
      return refuse(placement, decision ?? from?.decision, outcome.problem ?? "refused")
    }
    var c = caps(presentation)
    c.container = presentation.container
    let query = TemplateQuery(contract: spec.contract, caps: c)
    // A screen opens now or not at all: nothing downloads while the user waits.
    let result = await CacheOnly.$active.withValue(true) { await plan(query) }
    guard case .ready(var ready) = result else { return false }
    lock.lock()
    // Commit only onto the stack this screen was planned for: nothing else opened meanwhile, and
    // its parent is still showing. Anything else is discarded, uncounted.
    let expected = screens.count == depth - 1 && (depth == 1 || (from?.screenId != nil && screens.last?.id == from?.screenId))
    guard expected else { lock.unlock(); return false }
    ready.screenId = nextId
    screens.append(PresentedScreen(id: nextId, placement: placement, presentation: presentation, plan: ready, depth: depth))
    nextId += 1
    let now = screens
    lock.unlock()
    // Publish before anything that can suspend, so a dismiss meanwhile is never overwritten by
    // this older stack.
    onChange?(now)
    if let decision, let record = recordImpression { await record(decision, planner.clock()) }
    return true
  }

  private func refuse(_ placement: String, _ d: Decision?, _ reason: String) -> Bool {
    planner.events(TemplateEvent(type: .fallback, atEpochMs: planner.clock(), placement: placement, decisionId: d?.decisionId, detail: reason, experiments: d?.experiments ?? [:]))
    return false
  }
}
