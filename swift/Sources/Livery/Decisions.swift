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

/// The manifest's placement rules evaluated on the device (docs/PROTOCOL.md §8).
public struct LocalRulesDecider: Sendable {
  public let frequency: FrequencyStore
  public init(frequency: FrequencyStore = MemoryFrequencyStore()) { self.frequency = frequency }

  public func decide(_ placement: String, in manifest: Manifest?, context c: DecisionContext) async -> Decision? {
    var eligible: [PlacementRule] = []
    for r in manifest?.placements ?? [] where r.placement == placement {
      if await isEligible(r, c) { eligible.append(r) }
    }
    guard let rule = eligible.max(by: { a, b in a.priority != b.priority ? a.priority < b.priority : a.id > b.id }) else { return nil }
    return Decision(
      placement: placement, decisionId: "local:\(rule.id)", template: rule.template, data: rule.data,
      tracking: rule.tracking, experiments: rule.rollout.map { [$0.experiment: $0.arm] } ?? [:])
  }

  public func recordImpression(_ d: Decision, at: Int64) async {
    if d.decisionId.hasPrefix("local:") { await frequency.record(String(d.decisionId.dropFirst(6)), at: at) }
  }

  private func isEligible(_ r: PlacementRule, _ c: DecisionContext) async -> Bool {
    if let s = r.startEpochMs, c.nowEpochMs < s { return false }
    if let e = r.endEpochMs, c.nowEpochMs >= e { return false }
    let k = r.conditions
    if !k.platforms.isEmpty && !k.platforms.contains(c.platform) { return false }
    if !k.locales.isEmpty && !k.locales.contains(where: { c.locale == $0 || c.locale.hasPrefix("\($0)-") }) { return false }
    if let v = k.minAppVersion, c.appVersion < v { return false }
    if let v = k.maxAppVersion, c.appVersion > v { return false }
    if !k.segments.isSubset(of: c.segments) { return false }
    if !k.excludeSegments.isDisjoint(with: c.segments) { return false }
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
