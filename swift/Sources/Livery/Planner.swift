import Foundation

/// A document ready to play, the values to push (or write in), and what it is for.
public struct RenderPlan: Sendable {
  public var template: LoadedTemplate
  public var contract: Contract
  public var bindings: Bindings
  public var decision: Decision?
  public var feedItem: FeedItem?
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
public struct SlotPlanner: Sendable {
  public let repository: TemplateRepository
  public let events: EventSink
  public let clock: @Sendable () -> Int64

  public init(repository: TemplateRepository, events: @escaping EventSink = { _ in }, clock: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
    self.repository = repository; self.events = events; self.clock = clock
  }

  public func forAppData(_ query: TemplateQuery, data: [String: JSONValue], extra: Bindings = Bindings()) async -> PlanResult {
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

  private func plan(_ t: LoadedTemplate, _ data: [String: JSONValue], _ extra: Bindings, _ d: Decision?, _ f: FeedItem?) -> PlanResult {
    guard let contract = t.contract else { return .fallback("contract \(t.entry.contract) unknown") }
    switch ContractBinder.bind(contract, data) {
    case .ok(let b): return .ready(RenderPlan(template: t, contract: contract, bindings: b + extra, decision: d, feedItem: f))
    case .invalid(let problems):
      events(TemplateEvent(type: .error, atEpochMs: clock(), placement: d?.placement, decisionId: d?.decisionId, feedItemId: f?.id,
        designId: t.entry.designId, sha256: t.entry.sha256, detail: problems.map { "\($0.name): \($0.message)" }.joined(separator: ", ")))
      return .fallback("invalid data")
    }
  }

  private func fallback(_ q: TemplateQuery, _ reason: String, _ d: Decision?) -> PlanResult {
    events(TemplateEvent(type: .fallback, atEpochMs: clock(), placement: d?.placement, decisionId: d?.decisionId, detail: "\(q.contract): \(reason)"))
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
      feedItemId: plan.feedItem?.id, designId: plan.template.entry.designId, sha256: plan.template.entry.sha256, action: action)
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
