import Foundation
import Livery

extension CatalogSource {
  private struct SourceCatalogMeta: Decodable {
    let catalog: String
    let channel: String?
    let ttlSeconds: Int64?
    let expiresInDays: Int64?
  }

  private struct SourceSidecar: Decodable {
    let target: Target
    let reads: [String]?
    let emits: [String]?
    let fonts: [String]?
    let tags: [String: String]?
    let source: [String: String]?
  }

  private struct BuiltTemplateEntry: Encodable {
    let sha256: String
    let bytes: Int64
    let designId: String
    let contract: String
    let target: Target
    let tags: [String: String]
    let previews: [Preview]
    let revoked: Bool
    let reads: [String]
    let requiresMinor: Int
    let emits: [String]
    let assets: [String]
    let fonts: [String]
    let features: [String]
    let source: [String: String]
  }

  private struct BuiltManifest: Encodable {
    let schema: Int
    let catalog: String
    let channel: String
    let serial: Int64
    let generatedAtEpochMs: Int64
    let expiresAtEpochMs: Int64
    let ttlSeconds: Int64
    let minClient: Int
    let blobBase: String
    let previewBase: String
    let assetBase: String
    let contracts: [Contract]
    let designs: [Design]
    let templates: [BuiltTemplateEntry]
    let themes: [ThemePack]
    let placements: [PlacementRule]
    let experiments: [Experiment]
    let assets: [AssetEntry]
  }

  /// Builds a deterministic, offline bundled Livery catalog (`manifest.json` + `blobs/<sha256>.rc`)
  /// directly in pure Swift from this catalog source tree — no JVM or Gradle required for app-bundle
  /// builds. Use `livery build --inspect --sign` for production remote channels.
  @discardableResult
  public func buildBundled(
    to outputDirectory: URL,
    serial: Int64 = 0,
    generatedAtEpochMs: Int64 = 0,
    expiresAtEpochMs: Int64 = 4_102_444_800_000
  ) throws -> Manifest {
    let fm = FileManager.default
    let decoder = JSONDecoder()

    let metaURL = directory.appendingPathComponent("catalog.json")
    let meta = try decoder.decode(SourceCatalogMeta.self, from: Data(contentsOf: metaURL))

    let contracts: [Contract] = try loadJSONFiles(in: "contracts", as: Contract.self, decoder: decoder)
      .sorted { ($0.id, $0.major, $0.minor) < ($1.id, $1.major, $1.minor) }
    let themes: [ThemePack] = try loadJSONFiles(in: "themes", as: ThemePack.self, decoder: decoder)
      .sorted { $0.id < $1.id }
    let placements: [PlacementRule] = try loadJSONFiles(
      in: "placements", as: PlacementRule.self, decoder: decoder
    ).sorted { $0.id < $1.id }
    let experiments: [Experiment] = try loadJSONFiles(
      in: "experiments", as: Experiment.self, decoder: decoder
    ).sorted { $0.id < $1.id }

    let contractRefs = Set(contracts.map(\.ref))

    if fm.fileExists(atPath: outputDirectory.path) {
      try fm.removeItem(at: outputDirectory)
    }
    let blobsDir = outputDirectory.appendingPathComponent("blobs", isDirectory: true)
    try fm.createDirectory(at: blobsDir, withIntermediateDirectories: true)

    var designs: [Design] = []
    var templates: [BuiltTemplateEntry] = []

    let designsRoot = directory.appendingPathComponent("designs", isDirectory: true)
    if fm.fileExists(atPath: designsRoot.path) {
      let designFolders = try fm.contentsOfDirectory(
        at: designsRoot, includingPropertiesForKeys: [.isDirectoryKey]
      ).sorted { $0.lastPathComponent < $1.lastPathComponent }

      for folder in designFolders {
        var isDir: ObjCBool = false
        guard fm.fileExists(atPath: folder.path, isDirectory: &isDir), isDir.boolValue else {
          continue
        }
        let designFile = folder.appendingPathComponent("design.json")
        guard fm.fileExists(atPath: designFile.path) else { continue }
        let design = try decoder.decode(Design.self, from: Data(contentsOf: designFile))
        guard contractRefs.contains(design.contract) else {
          throw LiveryCaptureError.invalidIdentifier(design.contract)
        }
        designs.append(design)

        let files = try fm.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)
          .filter { $0.pathExtension == "rc" }
          .sorted { $0.lastPathComponent < $1.lastPathComponent }

        for rcURL in files {
          let stem = rcURL.deletingPathExtension().lastPathComponent
          let sidecarURL = folder.appendingPathComponent("\(stem).json")
          let rcBytes = try Data(contentsOf: rcURL)
          let sidecar = try decoder.decode(SourceSidecar.self, from: Data(contentsOf: sidecarURL))
          let sha256 = Hashing.sha256Hex(rcBytes)
          try rcBytes.write(
            to: blobsDir.appendingPathComponent("\(sha256).rc"), options: .atomic)

          templates.append(
            BuiltTemplateEntry(
              sha256: sha256,
              bytes: Int64(rcBytes.count),
              designId: design.id,
              contract: design.contract,
              target: sidecar.target,
              tags: sidecar.tags ?? [:],
              previews: [],
              revoked: false,
              reads: (sidecar.reads ?? []).sorted(),
              requiresMinor: 0,
              emits: (sidecar.emits ?? []).sorted(),
              assets: [],
              fonts: (sidecar.fonts ?? []).sorted(),
              features: [],
              source: sidecar.source ?? [:]
            )
          )
        }
      }
    }

    let built = BuiltManifest(
      schema: 1,
      catalog: meta.catalog,
      channel: meta.channel ?? "main",
      serial: serial,
      generatedAtEpochMs: generatedAtEpochMs,
      expiresAtEpochMs: expiresAtEpochMs,
      ttlSeconds: meta.ttlSeconds ?? 3600,
      minClient: 0,
      blobBase: "blobs/",
      previewBase: "previews/",
      assetBase: "assets/",
      contracts: contracts,
      designs: designs,
      templates: templates,
      themes: themes,
      placements: placements,
      experiments: experiments,
      assets: []
    )

    let encoder = JSONEncoder()
    encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
    var manifestBytes = try encoder.encode(built)
    manifestBytes.append(0x0A)
    try manifestBytes.write(
      to: outputDirectory.appendingPathComponent(Manifest.fileName), options: .atomic)

    return try decoder.decode(Manifest.self, from: manifestBytes)
  }

  private func loadJSONFiles<T: Decodable>(
    in subdirectory: String,
    as type: T.Type,
    decoder: JSONDecoder
  ) throws -> [T] {
    let fm = FileManager.default
    let dir = directory.appendingPathComponent(subdirectory, isDirectory: true)
    guard fm.fileExists(atPath: dir.path) else { return [] }
    let files = try fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)
      .filter { $0.pathExtension == "json" }
      .sorted { $0.lastPathComponent < $1.lastPathComponent }
    return try files.map { try decoder.decode(T.self, from: Data(contentsOf: $0)) }
  }
}
