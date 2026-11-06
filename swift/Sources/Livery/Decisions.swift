import Foundation

public struct DecisionContext: Sendable {
  public var nowEpochMs: Int64
  public var platform: String
  public var locale: String
  public var appVersion: Int64
  /// Stable, app-scoped, resettable; used only for sticky rollout buckets.
  public var bucketingId: String
  public var segments: Set<String>

  public init(nowEpochMs: Int64, platform: String = "ios", locale: String, appVersion: Int64, bucketingId: String, segments: Set<String> = []) {
    self.nowEpochMs = nowEpochMs; self.platform = platform; self.locale = locale
    self.appVersion = appVersion; self.bucketingId = bucketingId; self.segments = segments
  }
}

public protocol FrequencyStore: Sendable {
  func impressions(_ ruleId: String, since: Int64) async -> Int
  func record(_ ruleId: String, at: Int64) async
}

public actor MemoryFrequencyStore: FrequencyStore {
  private var log: [String: [Int64]] = [:]
  public init() {}
  public func impressions(_ ruleId: String, since: Int64) -> Int { log[ruleId]?.filter { $0 >= since }.count ?? 0 }
  public func record(_ ruleId: String, at: Int64) { log[ruleId, default: []].append(at) }
}

/// Who decides an experiment's arm: `LocalBucketing` by default, or a remote service. Return an arm id
/// of the experiment, or nil when there's no answer; never wait on the network.
public protocol ExperimentAssigner: Sendable {
  func assign(_ experiment: Experiment, context: DecisionContext) async -> String?
}

/// Sticky, offline assignment (§8.1): enrolled when `bucket(id, bucketingId) < allocationBasisPoints`,
/// in the arm whose cumulative weight covers `bucket(id + "#arm", bucketingId) × total / 10000`.
public struct LocalBucketing: ExperimentAssigner {
  public init() {}
  public func assign(_ experiment: Experiment, context: DecisionContext) async -> String? { Self.assignNow(experiment, context) }

  public static func assignNow(_ e: Experiment, _ c: DecisionContext) -> String? {
    guard running(e, c) else { return nil }
    let arms = e.arms.filter { $0.weight > 0 }
    let total = arms.reduce(Int64(0)) { $0 + Int64($1.weight) }
    let allocation = min(max(e.allocationBasisPoints, 0), 10_000)
    guard total > 0, allocation > 0, let last = arms.last else { return nil }
    guard LocalRulesDecider.bucket(e.id, c.bucketingId) < allocation else { return nil }
    let position = Int64(LocalRulesDecider.bucket(e.id + "#arm", c.bucketingId)) * total / 10_000
    var upTo: Int64 = 0
    for arm in arms {
      upTo += Int64(arm.weight)
      if position < upTo { return arm.id }
    }
    return last.id
  }

  public static func running(_ e: Experiment, _ c: DecisionContext) -> Bool {
    if let s = e.startEpochMs, c.nowEpochMs < s { return false }
    if let end = e.endEpochMs, c.nowEpochMs >= end { return false }
    return e.conditions.matches(c)
  }
}

extension Conditions {
  public func matches(_ c: DecisionContext) -> Bool {
    if !platforms.isEmpty && !platforms.contains(c.platform) { return false }
    if !locales.isEmpty && !locales.contains(where: { c.locale == $0 || c.locale.hasPrefix("\($0)-") }) { return false }
    if let v = minAppVersion, c.appVersion < v { return false }
    if let v = maxAppVersion, c.appVersion > v { return false }
    if !segments.isSubset(of: c.segments) { return false }
    if !excludeSegments.isDisjoint(with: c.segments) { return false }
    return true
  }
}

/// The manifest's placement rules evaluated on the device (docs/PROTOCOL.md §8).
public struct LocalRulesDecider: Sendable {
  public let frequency: FrequencyStore
  public let experiments: ExperimentAssigner
  public init(frequency: FrequencyStore = MemoryFrequencyStore(), experiments: ExperimentAssigner = LocalBucketing()) {
    self.frequency = frequency; self.experiments = experiments
  }

  public func decide(_ placement: String, in manifest: Manifest?, context c: DecisionContext) async -> Decision? {
    var eligible: [PlacementRule] = []
    for r in manifest?.placements ?? [] where r.placement == placement {
      if await isEligible(r, c, manifest) { eligible.append(r) }
    }
    guard let rule = eligible.max(by: { a, b in a.priority != b.priority ? a.priority < b.priority : a.id > b.id }) else { return nil }
    return Decision(
      placement: placement, decisionId: "local:\(rule.id)", template: rule.template, data: rule.data,
      tracking: rule.tracking, experiments: experimentsOf(rule))
  }

  private func experimentsOf(_ rule: PlacementRule) -> [String: String] {
    var e: [String: String] = [:]
    if let ro = rule.rollout { e[ro.experiment] = ro.arm }
    if let a = rule.arm { e[a.experiment] = a.arm }
    return e
  }

  public func recordImpression(_ d: Decision, at: Int64) async {
    if d.decisionId.hasPrefix("local:") { await frequency.record(String(d.decisionId.dropFirst(6)), at: at) }
  }

  private func isEligible(_ r: PlacementRule, _ c: DecisionContext, _ m: Manifest?) async -> Bool {
    if let s = r.startEpochMs, c.nowEpochMs < s { return false }
    if let e = r.endEpochMs, c.nowEpochMs >= e { return false }
    if !r.conditions.matches(c) { return false }
    if let ref = r.arm {
      guard let e = m?.experiments.first(where: { $0.id == ref.experiment }), LocalBucketing.running(e, c) else { return false }
      if await experiments.assign(e, context: c) != ref.arm { return false }
    }
    if let ro = r.rollout {
      let b = Self.bucket(ro.experiment, c.bucketingId)
      if !(b >= ro.fromBasisPoints && b < ro.toBasisPoints) { return false }
    }
    if let f = r.frequency, await frequency.impressions(r.id, since: c.nowEpochMs - f.perSeconds * 1000) >= f.maxImpressions { return false }
    return true
  }

  /// FNV-1a 32 of `<experiment>:<id>` (UTF-8), unsigned, modulo 10000.
  public static func bucket(_ experiment: String, _ id: String) -> Int {
    var h: UInt32 = 0x811C_9DC5
    for b in "\(experiment):\(id)".utf8 { h ^= UInt32(b); h = h &* 0x0100_0193 }
    return Int(h % 10_000)
  }
}
