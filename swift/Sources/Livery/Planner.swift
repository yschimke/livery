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
}

extension RenderPlan {
  /// One identity per rendered item: a different template, decision or feed item in the same slot
  /// is a different item, measured on its own (PROTOCOL.md §8). Views key themselves on it.
  public var renderIdentity: String {
    "\(template.entry.sha256)|\(decision?.decisionId ?? "")|\(feedItem?.id ?? "")"
  }
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
    if query.designId == nil, let experiments, let arm = await experiments.designFor(query.contract) {
      let tag = [arm.experiment: arm.arm]
      guard let design = arm.designId else {
        // An arm without a design (a control) draws the usual choice, tagged with its arm.
        guard let t = await repository.resolve(query) else { return fallback(query, "no template", nil) }
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
    guard let t = await repository.resolve(query) else { return fallback(query, "no template", nil) }
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

  public func dispatch(_ plan: RenderPlan, _ action: String, _ args: [String: String]) async {
    let base = TemplateEvent(type: .action, atEpochMs: clock(), placement: plan.decision?.placement, decisionId: plan.decision?.decisionId,
      feedItemId: plan.feedItem?.id, designId: plan.template.entry.designId, sha256: plan.template.entry.sha256, action: action, experiments: plan.experiments)
    func error(_ detail: String) { var e = base; e.type = .error; e.detail = detail; events(e) }
    guard let spec = plan.contract.actions[action] else { return error("undeclared action") }
    if spec.kind == ActionKinds.openURL && !(args["url"].map(urlAllowed) ?? false) { return error("url not allowed") }
    guard let handler = handlers[spec.kind] else { return error("no handler for \(spec.kind)") }
    events(base)
    await handler(plan, action, args)
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
