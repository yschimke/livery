import Foundation

extension PlayerCapabilities {
  /// Default capabilities for the native Apple UIKit/AppKit Remote Compose player (`RcNativePlayerUIKit`).
  public static let inAppNativeSwift = PlayerCapabilities(
    surface: Surfaces.inApp,
    maxRcApiLevel: 7,
    profiles: [],
    livePush: true,
    themes: [Target.themeHost]
  )
}

/// Convenience host facade for Apple apps: wires file-backed stores, a `TemplateRepository` and a
/// `SlotPlanner` with sensible defaults for the native Swift player.
public final class LiveryHost: Sendable {
  public let repository: TemplateRepository
  public let planner: SlotPlanner
  public let blobs: BlobStore
  public let kv: KeyValueStore
  public let directImageHosts: Set<String>
  public let events: EventSink

  public init(
    source: CatalogSource,
    trust: TrustPolicy,
    bundled: TemplateRepository.Bundled? = nil,
    targets: [PlayerCapabilities] = [.inAppNativeSwift],
    directImageHosts: Set<String> = [],
    fetcher: Fetcher = URLSessionFetcher(),
    experiments: Experiments? = nil,
    events: @escaping EventSink = { _ in },
    storageName: String = "livery"
  ) {
    let fm = FileManager.default
    let caches = (fm.urls(for: .cachesDirectory, in: .userDomainMask).first ?? fm.temporaryDirectory)
      .appendingPathComponent("\(storageName)-blobs", isDirectory: true)
    let support = (fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? fm.temporaryDirectory)
      .appendingPathComponent("\(storageName)-state", isDirectory: true)
    let blobs = FileBlobStore(directory: caches)
    let kv = FileKeyValueStore(directory: support)
    let repository = TemplateRepository(
      source: source,
      fetcher: fetcher,
      trust: trust,
      blobs: blobs,
      kv: kv,
      targets: targets,
      bundled: bundled
    )
    self.blobs = blobs
    self.kv = kv
    self.repository = repository
    self.planner = SlotPlanner(repository: repository, events: events, experiments: experiments)
    self.directImageHosts = directImageHosts
    self.events = events
  }

  /// Refreshes the catalog manifest and downloads templates matching active install policies.
  @discardableResult
  public func refresh(force: Bool = false) async -> RefreshResult {
    await repository.refresh(force: force)
  }

  /// Plans an app-owned slot against the default native Swift capabilities.
  public func plan(
    contract: String,
    data: [String: JSONValue],
    capabilities: PlayerCapabilities = .inAppNativeSwift,
    designId: String? = nil,
    variant: String? = nil,
    widthDp: Int? = nil,
    heightDp: Int? = nil,
    extra: Bindings = Bindings()
  ) async -> PlanResult {
    let query = TemplateQuery(
      contract: contract,
      capabilities: capabilities,
      widthDp: widthDp,
      heightDp: heightDp,
      designId: designId,
      variant: variant
    )
    return await planner.forAppData(query, data: data, extra: extra)
  }

  /// Reads verified asset bytes from the blob store (used by image and font resolvers).
  public func assetBytes(sha256: String) async -> Data? {
    await blobs.get(sha256)
  }

  /// The current catalog's configured `ImageProxy`, if any.
  public func imageProxy() async -> ImageProxy? {
    await repository.manifest()?.imageProxy
  }
}
