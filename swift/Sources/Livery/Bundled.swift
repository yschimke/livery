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

  /// Loads a built Livery catalog from a directory on disk (`manifest.json` plus relative paths).
  public static func fromDirectory(_ directory: URL) -> TemplateRepository.Bundled? {
    let manifestURL = directory.appendingPathComponent(Manifest.fileName)
    guard
      let data = try? Data(contentsOf: manifestURL),
      let manifest = try? JSONDecoder().decode(Manifest.self, from: data)
    else { return nil }
    let root = directory.standardizedFileURL
    return TemplateRepository.Bundled(manifest: manifest) { path in
      let clean = path.hasPrefix("/") ? String(path.dropFirst()) : path
      let target = root.appendingPathComponent(clean).standardizedFileURL
      guard target.path.hasPrefix(root.path) else { return nil }
      return try? Data(contentsOf: target)
    }
  }
}
