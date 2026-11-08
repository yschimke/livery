import Foundation

/// A document ready to play, the values to push (or write in), and what it is for.
public struct RenderPlan: Sendable {
  public var template: LoadedTemplate
  public var contract: Contract
  public var bindings: Bindings
  public var decision: Decision?
  public var feedItem: FeedItem?
  /// Experiment → arm for every experiment that shaped this plan (the decision's, a design arm's): on every event.
  public var experiments: [String: String] = [:]
  /// The `PresentedScreen` this plan is drawn in, set by `LiveryScreens`: which screen an action
  /// came from, however alike two screens' contents are. Nil in a slot.
  public var screenId: Int64? = nil
}

extension RenderPlan {
  /// One identity per rendered item: a different template, decision or feed item in the same slot
  /// is a different item, measured on its own (PROTOCOL.md §8). Views key themselves on it.
  public var renderIdentity: String {
    "\(template.entry.sha256)|\(decision?.decisionId ?? "")|\(feedItem?.id ?? "")"
  }

  /// A stable tag for the view a plan draws in, for UI tests and tap-tracking tools:
  /// `livery:<placement, or the contract>:<design>`, the same as Kotlin's `slotTag`.
  public var slotTag: String { "livery:\(decision?.placement ?? template.entry.contract):\(template.entry.designId)" }
}

public enum PlanResult: Sendable {
  case ready(RenderPlan)
  case fallback(String)
}

public typealias EventSink = @Sendable (TemplateEvent) -> Void

/// The three ways data reaches a template (the app's own, a decision, a feed item), all through
/// one contract gate: the Swift counterpart of the Kotlin `SlotPlanner`.
///
/// With `experiments`, a slot whose query names no design draws the design the user's arm picks for
/// the contract (§8.1); an arm that picks none (a control) draws the usual choice. Either way the
/// plan carries the arm, and its exposure is reported when it draws, through `Experiments.sink`.
public struct SlotPlanner: Sendable {
  public let repository: TemplateRepository
  public let events: EventSink
  public let clock: @Sendable () -> Int64
  public let experiments: Experiments?

  public init(repository: TemplateRepository, events: @escaping EventSink = { _ in }, clock: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }, experiments: Experiments? = nil) {
    self.repository = repository; self.events = events; self.clock = clock; self.experiments = experiments
  }

  public func forAppData(_ query: TemplateQuery, data: [String: JSONValue], extra: Bindings = Bindings()) async -> PlanResult {
    await appData(query, nil, data, extra)
  }

  /// §5.4: app-owned data for a slot that prefers a more specific design. `queries` are most specific
  /// first, all for one contract; the first with a template decides, and an experiment's design arm
  /// still applies within it.
  public func forAppData(_ queries: [TemplateQuery], data: [String: JSONValue], extra: Bindings = Bindings()) async -> PlanResult {
    precondition(!queries.isEmpty, "no queries")
    precondition(queries.allSatisfy { $0.contract == queries[0].contract }, "queries for more than one contract")
    // Nothing automatic for any query: the plain one still draws an arm's design that only an arm
    // may pick (`automatic = false`), exactly as forAppData(query) does.
    guard let found = await repository.resolveFirst(queries) else { return await appData(queries[queries.count - 1], nil, data, extra) }
    return await appData(queries[found.index], found.template, data, extra)
  }

  private func appData(_ query: TemplateQuery, _ resolved: LoadedTemplate?, _ data: [String: JSONValue], _ extra: Bindings) async -> PlanResult {
    func usual() async -> LoadedTemplate? {
      if let resolved { return resolved }
      return await repository.resolve(query)
    }
    if query.designId == nil, let experiments, let arm = await experiments.designFor(query.contract) {
      let tag = [arm.experiment: arm.arm]
      guard let design = arm.designId else {
        // An arm without a design (a control) draws the usual choice, tagged with its arm.
        guard let t = await usual() else { return fallback(query, "no template", nil) }
        return plan(t, data, extra, nil, nil, tag)
      }
      // An arm's design is automatic selection: never a skin nobody installed. Without a template
      // for this slot the usual choice draws, and the user wasn't exposed to the arm here.
      if await repository.automaticallySelectable(design) {
        var q = query
        q.designId = design
        if let t = await repository.resolve(q), t.entry.designId == design { return plan(t, data, extra, nil, nil, tag) }
      }
    }
    guard let t = await usual() else { return fallback(query, "no template", nil) }
    return plan(t, data, extra, nil, nil)
  }

  public func forDecision(_ query: TemplateQuery, _ decision: Decision, extra: Bindings = Bindings()) async -> PlanResult {
    guard let t = await repository.ensure(decision.template, query) else { return fallback(query, "no template for the decision", decision) }
    let ref = decision.template
    if (ref.sha256 != nil && t.entry.sha256 != ref.sha256) || (ref.designId != nil && t.entry.designId != ref.designId) {
      return fallback(query, "decided template unavailable", decision)
    }
    return plan(t, decision.data, extra, decision, nil)
  }

  public func forFeedItem(_ query: TemplateQuery, _ item: FeedItem, extra: Bindings = Bindings()) async -> PlanResult {
    var q = query
    if let c = item.template.contract { q.contract = c }
    guard let t = await repository.ensure(item.template, q) else { return fallback(query, "no template for item \(item.id)", nil) }
    return plan(t, item.data, extra, nil, item)
  }

  private func plan(_ t: LoadedTemplate, _ data: [String: JSONValue], _ extra: Bindings, _ d: Decision?, _ f: FeedItem?, _ arms: [String: String] = [:]) -> PlanResult {
    guard let contract = t.contract else { return .fallback("contract \(t.entry.contract) unknown") }
    let experiments = (d?.experiments ?? [:]).merging(arms) { _, arm in arm }
    switch ContractBinder.bind(contract, data) {
    case .ok(let b): return .ready(RenderPlan(template: t, contract: contract, bindings: b + extra, decision: d, feedItem: f, experiments: experiments))
    case .invalid(let problems):
      events(TemplateEvent(type: .error, atEpochMs: clock(), placement: d?.placement, decisionId: d?.decisionId, feedItemId: f?.id,
        designId: t.entry.designId, sha256: t.entry.sha256, detail: problems.map { "\($0.name): \($0.message)" }.joined(separator: ", "), experiments: experiments))
      return .fallback("invalid data")
    }
  }

  private func fallback(_ q: TemplateQuery, _ reason: String, _ d: Decision?) -> PlanResult {
    events(TemplateEvent(type: .fallback, atEpochMs: clock(), placement: d?.placement, decisionId: d?.decisionId, detail: "\(q.contract): \(reason)", experiments: d?.experiments ?? [:]))
    return .fallback(reason)
  }
}

/// Routes a template's host action by its contract kind (PROTOCOL.md §7); undeclared actions,
/// unregistered kinds and URLs outside the allowlist are dropped and reported.
public struct ActionRouter: Sendable {
  public typealias Handler = @Sendable (RenderPlan, String, [String: String]) async -> Void
  public let handlers: [String: Handler]
  public let urlAllowed: @Sendable (String) -> Bool
  public let events: EventSink
  public let clock: @Sendable () -> Int64

  public init(handlers: [String: Handler], urlAllowed: @escaping @Sendable (String) -> Bool, events: @escaping EventSink = { _ in }, clock: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
    self.handlers = handlers; self.urlAllowed = urlAllowed; self.events = events; self.clock = clock
  }

  /// Checks the action (`ActionGate`), then routes it by its kind. A `track` action is recorded and
  /// needs no handler; every event carries only the declared arguments, and a handler gets them all.
  public func dispatch(_ plan: RenderPlan, _ action: String, _ args: [String: String]) async {
    let check = ActionGate.check(plan.contract, action, args)
    let base = TemplateEvent(type: .action, atEpochMs: clock(), placement: plan.decision?.placement, decisionId: plan.decision?.decisionId,
      feedItemId: plan.feedItem?.id, designId: plan.template.entry.designId, sha256: plan.template.entry.sha256, action: action,
      experiments: plan.experiments, args: check.args)
    func error(_ detail: String) { var e = base; e.type = .error; e.detail = detail; events(e) }
    guard let spec = check.spec else { return error(check.problem ?? "undeclared action") }
    if let p = check.problem { return error(p) }
    if spec.kind == ActionKinds.openURL && !(args["url"].map(urlAllowed) ?? false) { return error("url not allowed") }
    let handler = handlers[spec.kind]
    if handler == nil && spec.kind != ActionKinds.track { return error("no handler for \(spec.kind)") }
    events(base)
    // A handler gets every argument, with the checked ones as the gate passed them (a `set` of a
    // bool arrives as true or false, not a document's 1 or 0).
    await handler?(plan, action, args.merging(check.args) { _, checked in checked })
  }

  /// PROTOCOL.md §7: a host named action's value as arguments: none, a JSON object of strings, or one value.
  public static func arguments(fromText text: String?) -> [String: String]? {
    guard let text, !text.trimmingCharacters(in: .whitespaces).isEmpty else { return [:] }
    guard text.trimmingCharacters(in: .whitespaces).hasPrefix("{") else { return ["value": text] }
    guard let obj = try? JSONDecoder().decode([String: JSONValue].self, from: Data(text.utf8)) else { return nil }
    var out: [String: String] = [:]
    for (k, v) in obj {
      switch v {
      case .string(let s): out[k] = s
      case .int(let i): out[k] = String(i)
      case .double(let d): out[k] = String(d)
      case .bool(let b): out[k] = String(b)
      default: return nil
      }
    }
    return out
  }
}

/// §7: an emitted action against its contract. `args` are the arguments the action declares,
/// checked against their types, and the only ones an event ever carries; `problem` is why the
/// action is dropped. The Swift counterpart of the Kotlin `ActionGate`, pinned by `conformance/actions.json`.
public struct ActionCheck: Equatable, Sendable {
  public var spec: ActionSpec?
  public var args: [String: String]
  public var problem: String?
}

public enum ActionGate {
  public static func check(_ contract: Contract, _ action: String, _ args: [String: String]) -> ActionCheck {
    guard let spec = contract.actions[action] else { return ActionCheck(spec: nil, args: [:], problem: "undeclared action") }
    if spec.kind == ActionKinds.set { return checkSet(contract, spec, args) }
    var declared: [String: String] = [:]
    for name in spec.args.keys.sorted() {
      let s = spec.args[name]!
      guard let value = args[name] else {
        if s.required { return ActionCheck(spec: spec, args: [:], problem: "missing argument '\(name)'") }
        continue
      }
      if let p = problem(name, s, value) { return ActionCheck(spec: spec, args: [:], problem: p) }
      declared[name] = value
    }
    return ActionCheck(spec: spec, args: declared, problem: nil)
  }

  /// A `set` (§7.1): its one argument, `value`, required and checked as the input it names. A bool
  /// also takes `1` and `0` (a document's boolean), passed on as `true` or `false`.
  private static func checkSet(_ contract: Contract, _ spec: ActionSpec, _ args: [String: String]) -> ActionCheck {
    guard let name = spec.input, let input = contract.inputs[name] else {
      return ActionCheck(spec: spec, args: [:], problem: "set names no input of the contract")
    }
    guard let raw = args["value"] else { return ActionCheck(spec: spec, args: [:], problem: "missing argument 'value'") }
    var value = raw
    if input.type == .bool { value = raw == "1" ? "true" : raw == "0" ? "false" : raw }
    if let p = problem("value", input, value) { return ActionCheck(spec: spec, args: [:], problem: p) }
    return ActionCheck(spec: spec, args: ["value": value], problem: nil)
  }

  private static func problem(_ name: String, _ s: InputSpec, _ value: String) -> String? {
    func number(_ n: Double?, _ type: String) -> String? {
      guard let n, n.isFinite else { return "argument '\(name)' is not \(type)" }
      if let min = s.min, n < min { return "argument '\(name)' is below \(min)" }
      if let max = s.max, n > max { return "argument '\(name)' is above \(max)" }
      return nil
    }
    switch s.type {
    case .string:
      if let max = s.maxLength, value.utf16.count > max { return "argument '\(name)' is longer than \(max)" }
      return nil
    case .int: return number(Int32(value).map(Double.init), "an int")
    case .long: return number(Int64(value).map(Double.init), "a long")
    case .float: return number(Double(value), "a float")
    case .bool: return value == "true" || value == "false" ? nil : "argument '\(name)' is not a bool"
    case .color, .image: return "argument '\(name)' is \(s.type.rawValue), which an action can't carry"
    }
  }
}
