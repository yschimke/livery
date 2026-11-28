import Foundation

extension TemplateRepository.Bundled {
  /// Loads a built Livery catalog from an app or framework `Bundle` (the Swift counterpart of
  /// `AndroidBundled.fromAssets`). Expects `manifest.json` beside `blobs/` and `assets/` under
  /// `subdirectory` (default `"livery"`).
  public static func fromBundle(
    _ bundle: Bundle = .main,
    subdirectory: String = "livery"
  ) -> TemplateRepository.Bundled? {
    let dir: URL?
    if subdirectory.isEmpty {
      dir = bundle.resourceURL
    } else {
      dir = bundle.url(forResource: subdirectory, withExtension: nil)
        ?? bundle.resourceURL?.appendingPathComponent(subdirectory, isDirectory: true)
    }
    guard let dir else { return nil }
    return fromDirectory(dir)
  }

  /// Loads a built Livery catalog from a directory on disk (`manifest.json` plus `blobs/` and
  /// `assets/`). Maps the SHA-256 requested by `TemplateRepository` to the catalog's blob or asset
  /// filename under `directory`.
  public static func fromDirectory(_ directory: URL) -> TemplateRepository.Bundled? {
    let manifestURL = directory.appendingPathComponent(Manifest.fileName)
    guard
      let data = try? Data(contentsOf: manifestURL),
      let manifest = try? JSONDecoder().decode(Manifest.self, from: data)
    else { return nil }
    let root = directory.standardizedFileURL
    let assetNames: [String: String] = Dictionary(
      uniqueKeysWithValues: manifest.assets.map { ($0.sha256, $0.fileName) }
    )
    let blobBase = relativePrefix(manifest.blobBase, default: "blobs/")
    let assetBase = relativePrefix(manifest.assetBase, default: "assets/")

    return TemplateRepository.Bundled(manifest: manifest) { sha256 in
      let candidates: [String]
      if let assetFile = assetNames[sha256] {
        candidates = [assetBase + assetFile, "assets/" + assetFile, assetFile]
      } else {
        candidates = [blobBase + "\(sha256).rc", "blobs/\(sha256).rc", "\(sha256).rc"]
      }
      for relative in candidates {
        let target = root.appendingPathComponent(relative).standardizedFileURL
        guard target.path.hasPrefix(root.path) else { continue }
        if let bytes = try? Data(contentsOf: target) {
          return bytes
        }
      }
      return nil
    }
  }

  private static func relativePrefix(_ base: String, default fallback: String) -> String {
    guard !base.contains("://") else { return fallback }
    let clean = base.hasPrefix("/") ? String(base.dropFirst()) : base
    if clean.isEmpty { return "" }
    return clean.hasSuffix("/") ? clean : clean + "/"
  }
}
