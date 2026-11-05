import Foundation

/// Where a catalog channel's manifest lives; everything else resolves against it (PROTOCOL.md §1).
public protocol CatalogSource: Sendable {
  var id: String { get }
  var manifestURL: String { get }
}

extension CatalogSource {
  public var signatureURL: String { ResourceURLs.resolve(manifestURL, Manifest.signatureFileName) }
}

/// Any static host: Pages, S3, a CDN, a design server channel.
public struct StaticSource: CatalogSource {
  public let baseURL: String
  public let id: String
  public init(_ baseURL: String, id: String? = nil) { self.baseURL = baseURL; self.id = id ?? baseURL }
  public var manifestURL: String { (baseURL.hasSuffix("/") ? String(baseURL.dropLast()) : baseURL) + "/manifest.json" }
}

/// A GitHub release; `latest` follows the newest release.
public struct GitHubReleaseSource: CatalogSource {
  public let owner: String, repo: String, tag: String
  public init(owner: String, repo: String, tag: String = "latest") { self.owner = owner; self.repo = repo; self.tag = tag }
  public var id: String { "github:\(owner)/\(repo)@\(tag)" }
  public var manifestURL: String {
    tag == "latest"
      ? "https://github.com/\(owner)/\(repo)/releases/latest/download/manifest.json"
      : "https://github.com/\(owner)/\(repo)/releases/download/\(tag)/manifest.json"
  }
}

public struct LoadedTemplate: Sendable {
  public enum Origin: Sendable { case fresh, stale, bundled }
  public var entry: TemplateEntry
  public var design: Design?
  public var bytes: Data
  public var origin: Origin
  /// The contract from the catalog the template came from (the bundled one, offline).
  public var contract: Contract?
  /// Its images and fonts, all available when it is handed out.
  public var assets: [AssetEntry]

  /// The exact face, else the nearest weight of the same style in the family (PROTOCOL.md §6.3).
  public func fontAsset(_ face: FontFace) -> AssetEntry? {
    let family = assets.filter { $0.kind == .font && $0.font?.family.lowercased() == face.family.lowercased() }
    if let exact = family.first(where: { $0.font?.weight == face.weight && $0.font?.italic == face.italic }) { return exact }
    return family.filter { $0.font?.italic == face.italic }.min { abs(($0.font?.weight ?? 0) - face.weight) < abs(($1.font?.weight ?? 0) - face.weight) }
  }
}

public struct DesignSummary: Sendable {
  public var design: Design
  public var previews: [(preview: Preview, url: String)]
  public var installed: Bool
  public var downloadBytes: Int64
  public var playable: Bool
}

public enum RefreshResult: Sendable, Equatable {
  case updated(serial: Int64)
  case unchanged
  case skipped(String)
  case failed(String)
}

/// Fetches, verifies, caches and selects templates for one catalog channel, with a bundled copy to
/// fall back on: the Swift counterpart of the Kotlin `TemplateRepository`. Drawing never waits on
/// the network: `resolve` reads only what is on disk, and a refresh in flight blocks nothing (the
/// actor runs other calls while it awaits).
public actor TemplateRepository {
  public struct Bundled: Sendable {
    public let manifest: Manifest
    public let read: @Sendable (String) async -> Data?
    public init(manifest: Manifest, read: @escaping @Sendable (String) async -> Data?) { self.manifest = manifest; self.read = read }
  }

  /// PROTOCOL.md §2.1: level 2 added resources (assets, fonts, the image proxy); level 3 added
  /// contract minors (`requiresMinor`, `TemplateQuery.minor`).
  public static let protocolLevel = 3
  public static let staleGraceMs: Int64 = 30 * 24 * 3_600_000
  static let backoffMs: [Int64] = [60_000, 3_600_000, 4 * 3_600_000, 24 * 3_600_000]

  private struct Meta: Codable {
    var etag: String?
    var acceptedSerial: Int64 = 0
    var fetchedAtEpochMs: Int64 = 0
    var failures: Int = 0
    var nextAttemptEpochMs: Int64 = 0
  }

  public let source: CatalogSource
  private let fetcher: Fetcher
  private let verifier: ManifestVerifier
  private let blobs: BlobStore
  private let kv: KeyValueStore
  private let clock: @Sendable () -> Int64
  private let targets: [PlayerCapabilities]
  private let bundled: Bundled?
  private var current: Manifest?
  private var refreshing: Task<RefreshResult, Never>?

  public init(
    source: CatalogSource, fetcher: Fetcher = URLSessionFetcher(), trust: TrustPolicy, blobs: BlobStore, kv: KeyValueStore,
    clock: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }, targets: [PlayerCapabilities], bundled: Bundled? = nil
  ) {
    self.source = source; self.fetcher = fetcher; self.verifier = ManifestVerifier(policy: trust); self.blobs = blobs; self.kv = kv
    self.clock = clock; self.targets = targets; self.bundled = bundled
  }

  private func k(_ name: String) -> String { "livery/\(source.id)/\(name)" }

  public func manifest() async -> Manifest? {
    if let current { return current }
    guard let text = await kv.get(k("manifest")), let m = try? JSONDecoder().decode(Manifest.self, from: Data(text.utf8)) else { return nil }
    current = m
    return m
  }

  private func meta() async -> Meta {
    guard let text = await kv.get(k("meta")), let m = try? JSONDecoder().decode(Meta.self, from: Data(text.utf8)) else { return Meta() }
    return m
  }

  private func putMeta(_ m: Meta) async {
    if let data = try? JSONEncoder().encode(m) { await kv.put(k("meta"), String(decoding: data, as: UTF8.self)) }
  }

  // MARK: Refresh

  /// Revalidates (ETag), verifies (§3), refuses rollbacks, then downloads what install policies
  /// ask for. Concurrent calls share one refresh. `force` ignores the TTL and backoff.
  public func refresh(force: Bool = false) async -> RefreshResult {
    if let refreshing { return await refreshing.value }
    let task = Task { await self.doRefresh(force: force) }
    refreshing = task
    let result = await task.value
    refreshing = nil
    return result
  }

  private func doRefresh(force: Bool) async -> RefreshResult {
    let now = clock()
    let m = await meta()
    let cached = await manifest()
    if !force {
      if now < m.nextAttemptEpochMs { return .skipped("backing off") }
      if let cached, now - m.fetchedAtEpochMs < cached.ttlSeconds * 1000, now < cached.expiresAtEpochMs {
        await prefetch(cached)
        return .skipped("fresh")
      }
    }
    func fail(_ reason: String, _ retryAfter: Int64? = nil) async -> RefreshResult {
      var next = m
      next.failures += 1
      next.nextAttemptEpochMs = now + (retryAfter.map { $0 * 1000 } ?? Self.backoffMs[min(next.failures - 1, Self.backoffMs.count - 1)])
      await putMeta(next)
      return .failed(reason)
    }
    switch await fetcher.get(source.manifestURL, etag: cached != nil ? m.etag : nil) {
    case .failed(let status, let message, let retry):
      return await fail("manifest: \(status.map(String.init) ?? "-") \(message)", retry)
    case .notModified:
      var next = m
      next.fetchedAtEpochMs = now; next.failures = 0; next.nextAttemptEpochMs = 0
      await putMeta(next)
      if let cached { await prefetch(cached) }
      return .unchanged
    case .ok(let bytes, let etag):
      var signature: ManifestSignature?
      if case .ok(let s, _) = await fetcher.get(source.signatureURL, etag: nil) { signature = try? JSONDecoder().decode(ManifestSignature.self, from: s) }
      switch verifier.verify(bytes, signature: signature) {
      case .rejected(let reason): return await fail(reason)
      case .accepted(let next, _):
        if next.serial < m.acceptedSerial { return await fail("rollback: serial \(next.serial) < \(m.acceptedSerial)") }
        if next.minClient > Self.protocolLevel { return await fail("needs client level \(next.minClient)") }
        await kv.put(k("manifest"), String(decoding: bytes, as: UTF8.self))
        current = next
        await putMeta(Meta(etag: etag, acceptedSerial: next.serial, fetchedAtEpochMs: now))
        await prefetch(next)
        return .updated(serial: next.serial)
      }
    }
  }

  // MARK: Selection

  private func usable() async -> Manifest? {
    guard let m = await manifest(), clock() < m.expiresAtEpochMs + Self.staleGraceMs else { return nil }
    return m
  }

  private func origin(_ m: Manifest) -> LoadedTemplate.Origin { clock() < m.expiresAtEpochMs ? .fresh : .stale }

  /// From disk only: the usable cached manifest, choosing among what is downloaded, else the
  /// bundled catalog. Nil means the app draws its own fallback.
  public func resolve(_ query: TemplateQuery) async -> LoadedTemplate? {
    let installed = await installedIds()
    let automatic: (Design) -> Bool = { $0.install == .always || installed.contains($0.id) }
    if let m = await usable() {
      let have = await blobs.keys()
      var cached = m
      cached.templates = m.templates.filter { t in have.contains(t.sha256) && t.assets.allSatisfy(have.contains) }
      var queries = [query]
      if query.designId != nil { var q = query; q.designId = nil; queries.append(q) }
      for q in queries {
        guard let e = TemplateSelector.select(cached, q, automatic: automatic), let bytes = await blobs.get(e.sha256) else { continue }
        return loaded(m, e, bytes, origin(m))
      }
    }
    guard let b = bundled else { return nil }
    var plain = query
    plain.designId = nil
    guard let e = TemplateSelector.select(b.manifest, query, automatic: automatic) ?? TemplateSelector.select(b.manifest, plain, automatic: automatic),
      let bytes = await b.read(e.sha256)
    else { return nil }
    return loaded(b.manifest, e, bytes, .bundled)
  }

  /// A decision's or feed item's reference (§5.3): an exact `sha256` plays exactly or not at all.
  public func ensure(_ ref: TemplateRef, _ query: TemplateQuery) async -> LoadedTemplate? {
    let m = await usable()
    if let sha = ref.sha256 {
      // The same bytes may be listed for several targets or designs: any matching, eligible entry will do.
      guard let m, let e = m.templates.first(where: { $0.sha256 == sha && (ref.designId == nil || $0.designId == ref.designId) && TemplateSelector.eligible(m, $0, query) }),
        let bytes = await obtain(m, e)
      else { return nil }
      return loaded(m, e, bytes, origin(m))
    }
    guard let designId = ref.designId else { return await resolve(query) }
    var q = query
    q.designId = designId
    if let m, let e = TemplateSelector.select(m, q, automatic: { _ in false }), let bytes = await obtain(m, e) {
      return loaded(m, e, bytes, origin(m))
    }
    let fallback = await resolve(q)
    return fallback?.entry.designId == designId ? fallback : nil
  }

  private func loaded(_ m: Manifest, _ e: TemplateEntry, _ bytes: Data, _ origin: LoadedTemplate.Origin) -> LoadedTemplate {
    LoadedTemplate(entry: e, design: m.design(e.designId), bytes: bytes, origin: origin, contract: m.contract(e.contract), assets: m.assets(for: e))
  }

  // MARK: Install, list, preview

  public func designs(_ contract: String) async -> [DesignSummary] {
    guard let m = await manifest() ?? bundled?.manifest else { return [] }
    let installed = await installedIds()
    let have = await blobs.keys()
    return m.designs.filter { $0.contract == contract && !$0.revoked }.map { d in
      let needed = entries(m, d.id)
      let assets = Dictionary(needed.flatMap { m.assets(for: $0) }.map { ($0.sha256, $0) }, uniquingKeysWith: { a, _ in a }).values
      let previews = (d.previews + needed.flatMap(\.previews)).reduce(into: [Preview]()) { acc, p in if !acc.contains(where: { $0.sha256 == p.sha256 }) { acc.append(p) } }
      return DesignSummary(
        design: d,
        previews: previews.map { ($0, ResourceURLs.resolve(source.manifestURL, m.previewBase + $0.fileName)) },
        installed: d.install == .always || installed.contains(d.id),
        downloadBytes: needed.filter { !have.contains($0.sha256) }.reduce(0) { $0 + $1.bytes } + assets.filter { !have.contains($0.sha256) }.reduce(0) { $0 + $1.bytes },
        playable: !needed.isEmpty)
    }
  }

  /// Downloads `designId` for every configured target, then records it; a failure installs nothing.
  public func install(_ designId: String) async -> Bool {
    guard let m = await manifest() else { return false }
    let needed = entries(m, designId)
    if needed.isEmpty { return false }
    for e in needed { if await obtain(m, e) == nil { return false } }
    await putSet("installed", await installedIds().union([designId]))
    return true
  }

  public func uninstall(_ designId: String) async { await putSet("installed", await installedIds().subtracting([designId])) }

  public func installedIds() async -> Set<String> { await getSet("installed") }

  /// A design that isn't installed, playable once without installing it.
  public func preview(_ designId: String, _ query: TemplateQuery) async -> LoadedTemplate? {
    var q = query
    q.designId = designId
    let t = await ensure(TemplateRef(designId: designId), q)
    return t?.entry.designId == designId ? t : nil
  }

  /// An asset's bytes (for a player's font or image loader).
  public func asset(_ sha256: String) async -> Data? {
    if let d = await blobs.get(sha256) { return d }
    return await bundled?.read(sha256)
  }

  public func retained() async -> Set<String> {
    guard let m = await manifest() else { return [] }
    let installed = await installedIds()
    let es = m.designs.filter { $0.install == .always || installed.contains($0.id) }.flatMap { entries(m, $0.id) }
    return Set(es.map(\.sha256) + es.flatMap(\.assets))
  }

  /// Deletes blobs this repository downloaded that no `Always` or installed design needs.
  public func prune(keep: Set<String> = []) async {
    let retained = await retained().union(keep)
    let owned = await getSet("owned")
    let drop = owned.subtracting(retained)
    for sha in drop { await blobs.delete(sha) }
    await putSet("owned", owned.subtracting(drop))
  }

  // MARK: Internals

  private func entries(_ m: Manifest, _ designId: String) -> [TemplateEntry] {
    m.templates(for: designId).filter { e in !e.revoked && targets.contains { e.target.fits($0) && e.bytes <= $0.maxBytes } }
  }

  private func prefetch(_ m: Manifest) async {
    let installed = await installedIds()
    for d in m.designs where !d.revoked && (d.install == .always || installed.contains(d.id)) {
      for e in entries(m, d.id) { _ = await obtain(m, e) }
    }
  }

  /// The template's bytes once it and all its assets are on disk, downloading what is missing.
  private func obtain(_ m: Manifest, _ e: TemplateEntry) async -> Data? {
    for sha in e.assets {
      if await blobs.get(sha) != nil { continue }
      guard let a = m.asset(sha) else { return nil }
      if await fetchVerified(ResourceURLs.resolve(source.manifestURL, m.assetBase + a.fileName), a.sha256, a.bytes) == nil { return nil }
    }
    if let b = await blobs.get(e.sha256) { return b }
    return await fetchVerified(ResourceURLs.resolve(source.manifestURL, m.blobBase + e.sha256 + ".rc"), e.sha256, e.bytes)
  }

  private func fetchVerified(_ url: String, _ sha256: String, _ size: Int64) async -> Data? {
    guard case .ok(let data, _) = await fetcher.get(url, etag: nil), Int64(data.count) == size, Hashing.sha256Hex(data) == sha256 else { return nil }
    await blobs.put(sha256, data)
    await putSet("owned", await getSet("owned").union([sha256]))
    return data
  }

  private func getSet(_ name: String) async -> Set<String> {
    guard let text = await kv.get(k(name)), let s = try? JSONDecoder().decode(Set<String>.self, from: Data(text.utf8)) else { return [] }
    return s
  }

  private func putSet(_ name: String, _ value: Set<String>) async {
    if let data = try? JSONEncoder().encode(value.sorted()) { await kv.put(k(name), String(decoding: data, as: UTF8.self)) }
  }
}
