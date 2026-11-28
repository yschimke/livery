import Foundation
import Livery
import RcCreation

/// One captured Remote Compose template: its `.rc` bytes and the sidecar metadata `livery build`
/// reads beside it (the Swift counterpart of `livery-capture-core`'s `CapturedTemplate`).
public struct CapturedTemplate: Equatable, Sendable {
  public let bytes: Data
  public let target: Target
  public let reads: [String]
  public let emits: [String]
  public let fonts: [String]
  public let source: [String: String]

  public init(
    bytes: Data,
    target: Target,
    reads: [String],
    emits: [String],
    fonts: [String] = [],
    source: [String: String] = ["tool": "swift-capture"]
  ) {
    self.bytes = bytes
    self.target = target
    self.reads = reads.sorted()
    self.emits = emits.sorted()
    self.fonts = Array(Set(fonts)).sorted()
    self.source = source
  }

  private struct Sidecar: Encodable {
    let target: Target
    let reads: [String]
    let emits: [String]
    let fonts: [String]?
    let source: [String: String]?
  }

  /// Encodes the `<name>.json` sidecar expected by `livery build`.
  public func sidecarJSON() throws -> Data {
    let payload = Sidecar(
      target: target,
      reads: reads,
      emits: emits,
      fonts: fonts.isEmpty ? nil : fonts,
      source: source.isEmpty ? nil : source
    )
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
    var data = try encoder.encode(payload)
    data.append(0x0A)
    return data
  }
}

/// Captures Swift `RcCreation` views into Livery `.rc` templates validated against a `Contract`.
public enum LiveryCapture {
  public static func capture<Content: RemoteView>(
    contract: Contract,
    target: Target = Target(surface: Surfaces.inApp, rcApiLevel: 7),
    width: Double,
    height: Double,
    displayScale: Double = 2,
    accessibilityLabel: String? = nil,
    fonts: [String] = [],
    source: [String: String] = [:],
    content: (LiveryValues) throws -> Content
  ) throws -> CapturedTemplate {
    let values = LiveryValues(contract: contract)
    let built = try content(values)
    let document = RemoteDocument(
      width: width,
      height: height,
      displayScale: displayScale,
      accessibilityLabel: accessibilityLabel
    ) {
      built
    }
    let encoded = try document.encode()
    var mergedSource = ["tool": "swift-capture"]
    for (k, v) in source { mergedSource[k] = v }
    return CapturedTemplate(
      bytes: Data(encoded),
      target: target,
      reads: values.reads,
      emits: values.emits,
      fonts: fonts,
      source: mergedSource
    )
  }
}

/// Writes a design's captured templates into `designs/<id>/` (`<name>.rc` + `<name>.json`),
/// removing stale template files no longer captured.
public struct DesignFolder: Sendable {
  public let directory: URL

  private static let reservedNames: Set<String> = ["design", "previews", "assets"]

  public init(directory: URL) {
    self.directory = directory
  }

  public func write(_ templates: [String: CapturedTemplate]) throws {
    for name in templates.keys {
      guard Self.isValidName(name), !Self.reservedNames.contains(name) else {
        throw LiveryCaptureError.invalidTemplateName(name)
      }
    }
    let fm = FileManager.default
    try fm.createDirectory(at: directory, withIntermediateDirectories: true)
    let existing = (try? fm.contentsOfDirectory(atPath: directory.path)) ?? []
    for file in existing {
      guard file != "design.json" else { continue }
      if file.hasSuffix(".rc") || file.hasSuffix(".json") {
        let stem = (file as NSString).deletingPathExtension
        if templates[stem] == nil {
          try? fm.removeItem(at: directory.appendingPathComponent(file))
        }
      }
    }
    for (name, template) in templates {
      try template.bytes.write(to: directory.appendingPathComponent("\(name).rc"), options: .atomic)
      try template.sidecarJSON().write(
        to: directory.appendingPathComponent("\(name).json"), options: .atomic)
    }
  }

  static func isValidName(_ name: String) -> Bool {
    guard !name.isEmpty, name.count <= 64 else { return false }
    let bytes = Array(name.utf8)
    guard let first = bytes.first,
      (first >= 0x61 && first <= 0x7A) || (first >= 0x30 && first <= 0x39)
    else { return false }
    return bytes.allSatisfy { b in
      (b >= 0x61 && b <= 0x7A) || (b >= 0x30 && b <= 0x39) || b == 0x2E || b == 0x5F || b == 0x2D
    }
  }
}

/// Writes a Livery catalog source tree (`livery build`'s input) from Swift: `catalog.json`,
/// `contracts/*.json`, and `designs/<id>/design.json` beside captured templates.
public struct CatalogSource: Sendable {
  public let directory: URL

  public init(directory: URL) {
    self.directory = directory
  }

  private struct CatalogMeta: Encodable {
    let catalog: String
    let channel: String
    let ttlSeconds: Int64
    let expiresInDays: Int64
  }

  public func writeCatalog(
    _ catalog: String,
    channel: String = "main",
    ttlSeconds: Int64 = 3600,
    expiresInDays: Int64 = 14
  ) throws {
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let data = try encodeJSON(
      CatalogMeta(
        catalog: catalog, channel: channel, ttlSeconds: ttlSeconds, expiresInDays: expiresInDays))
    try data.write(to: directory.appendingPathComponent("catalog.json"), options: .atomic)
  }

  public func writeContract(_ contract: Contract, fileName: String? = nil) throws {
    let name = fileName ?? String(contract.id.split(separator: "/").last ?? Substring(contract.id))
    guard DesignFolder.isValidName(name) else {
      throw LiveryCaptureError.invalidIdentifier(name)
    }
    let dir = directory.appendingPathComponent("contracts", isDirectory: true)
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    try encodeJSON(contract).write(
      to: dir.appendingPathComponent("\(name).json"), options: .atomic)
  }

  @discardableResult
  public func writeDesign(
    _ design: Design,
    templates: [String: CapturedTemplate]
  ) throws -> DesignFolder {
    guard DesignFolder.isValidName(design.id) else {
      throw LiveryCaptureError.invalidIdentifier(design.id)
    }
    let dir = directory
      .appendingPathComponent("designs", isDirectory: true)
      .appendingPathComponent(design.id, isDirectory: true)
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    try encodeJSON(design).write(to: dir.appendingPathComponent("design.json"), options: .atomic)
    let folder = DesignFolder(directory: dir)
    try folder.write(templates)
    return folder
  }

  /// Copies hand-written catalog files (e.g. `catalog.json`, `themes/`, `placements/`, `fonts/`)
  /// from `handWritten` into `directory` before writing captured contracts and designs.
  public func copyHandWritten(from handWritten: URL) throws {
    let fm = FileManager.default
    guard fm.fileExists(atPath: handWritten.path) else { return }
    try fm.createDirectory(at: directory, withIntermediateDirectories: true)
    let entries = try fm.contentsOfDirectory(at: handWritten, includingPropertiesForKeys: nil)
    for src in entries {
      let dst = directory.appendingPathComponent(src.lastPathComponent)
      if fm.fileExists(atPath: dst.path) { try fm.removeItem(at: dst) }
      try fm.copyItem(at: src, to: dst)
    }
  }

  private func encodeJSON<T: Encodable>(_ value: T) throws -> Data {
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
    var data = try encoder.encode(value)
    data.append(0x0A)
    return data
  }
}
