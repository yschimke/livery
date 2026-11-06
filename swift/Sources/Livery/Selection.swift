import Foundation

/// What a slot asks for (docs/PROTOCOL.md §5).
public struct TemplateQuery: Sendable, Equatable {
  public var contract: String
  public var caps: PlayerCapabilities
  public var widthDp: Int?
  public var heightDp: Int?
  public var designId: String?
  public var variant: String?
  public var theme: String?
  public var tags: [String: String]
  /// The contract minor the app implements; templates needing a newer one are skipped. Nil accepts any.
  public var minor: Int?

  public init(contract: String, caps: PlayerCapabilities, widthDp: Int? = nil, heightDp: Int? = nil, designId: String? = nil, variant: String? = nil, theme: String? = nil, tags: [String: String] = [:], minor: Int? = nil) {
    self.contract = contract; self.caps = caps; self.widthDp = widthDp; self.heightDp = heightDp
    self.designId = designId; self.variant = variant; self.theme = theme; self.tags = tags; self.minor = minor
  }
}

/// Pure selection over a manifest: the same answer as the Kotlin `TemplateSelector`.
public enum TemplateSelector {
  /// §5.1: whether `t` may play for `query`. Exact references pass this same check.
  public static func eligible(_ manifest: Manifest, _ t: TemplateEntry, _ query: TemplateQuery) -> Bool {
    guard let design = manifest.designs.first(where: { $0.id == t.designId && !$0.revoked && $0.contract == query.contract }) else { return false }
    return !t.revoked && t.contract == query.contract
      && (query.minor.map { t.requiresMinor <= $0 } ?? true)
      && t.bytes <= query.caps.maxBytes
      && t.target.themeFits(query.caps, wanted: query.theme)
      && t.target.fits(query.caps, widthDp: query.widthDp, heightDp: query.heightDp)
      && query.tags.allSatisfy { t.tags[$0.key] == $0.value || design.tags[$0.key] == $0.value }
  }

  /// §5.2. `automatic` says which designs may be chosen without being named (`Always` or installed).
  public static func select(_ manifest: Manifest, _ query: TemplateQuery, automatic: (Design) -> Bool = { _ in true }) -> TemplateEntry? {
    let candidates = manifest.templates.filter { eligible(manifest, $0, query) }
    if candidates.isEmpty { return nil }
    let designs = Dictionary(manifest.designs.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })

    func best(_ of: [TemplateEntry]) -> TemplateEntry? {
      of.min { a, b in
        if a.target.rcApiLevel != b.target.rcApiLevel { return a.target.rcApiLevel > b.target.rcApiLevel }
        let am = query.theme != nil && a.target.theme == query.theme, bm = query.theme != nil && b.target.theme == query.theme
        if am != bm { return am }
        if a.target.size.span != b.target.size.span { return a.target.size.span < b.target.size.span }
        return a.sha256 < b.sha256
      }
    }
    if let id = query.designId, let e = best(candidates.filter { $0.designId == id }) { return e }
    let auto = candidates.filter { designs[$0.designId].map(automatic) ?? false }
    func byVariant(_ v: String) -> [TemplateEntry] { auto.filter { designs[$0.designId]?.variant == v } }
    if let v = query.variant, let e = best(byVariant(v)) { return e }
    if let e = best(byVariant(Design.defaultVariant)) { return e }
    return best(auto)
  }
}
