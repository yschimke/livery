import Foundation

// The Livery protocol's wire types (docs/PROTOCOL.md), with the same JSON names and defaults as the
// Kotlin reference in livery-protocol. Unknown keys are ignored; missing keys take their defaults.

extension KeyedDecodingContainer {
  func value<T: Decodable>(_ key: Key, _ fallback: @autoclosure () -> T) throws -> T {
    try decodeIfPresent(T.self, forKey: key) ?? fallback()
  }
}

public struct Manifest: Codable, Equatable, Sendable {
  public static let fileName = "manifest.json"
  public static let signatureFileName = "manifest.sig.json"

  public var schema: Int
  public var catalog: String
  public var channel: String
  public var serial: Int64
  public var generatedAtEpochMs: Int64
  public var expiresAtEpochMs: Int64
  public var ttlSeconds: Int64
  public var minClient: Int
  public var blobBase: String
  public var previewBase: String
  public var assetBase: String
  public var contracts: [Contract]
  public var designs: [Design]
  public var templates: [TemplateEntry]
  public var themes: [ThemePack]
  public var placements: [PlacementRule]
  /// A/B tests over designs and placements (§8.1).
  public var experiments: [Experiment]
  public var assets: [AssetEntry]
  public var imageProxy: ImageProxy?
  public var provenance: [String: String]

  enum CodingKeys: String, CodingKey {
    case schema, catalog, channel, serial, generatedAtEpochMs, expiresAtEpochMs, ttlSeconds, minClient
    case blobBase, previewBase, assetBase, contracts, designs, templates, themes, placements, experiments, assets, imageProxy, provenance
  }

  public init(from d: Decoder) throws {
    let c = try d.container(keyedBy: CodingKeys.self)
    schema = try c.value(.schema, 1)
    catalog = try c.decode(String.self, forKey: .catalog)
    channel = try c.value(.channel, "main")
    serial = try c.decode(Int64.self, forKey: .serial)
    generatedAtEpochMs = try c.decode(Int64.self, forKey: .generatedAtEpochMs)
    expiresAtEpochMs = try c.decode(Int64.self, forKey: .expiresAtEpochMs)
    ttlSeconds = try c.value(.ttlSeconds, 3600)
    minClient = try c.value(.minClient, 0)
    blobBase = try c.value(.blobBase, "blobs/")
    previewBase = try c.value(.previewBase, "previews/")
    assetBase = try c.value(.assetBase, "assets/")
    contracts = try c.value(.contracts, [])
    designs = try c.value(.designs, [])
    templates = try c.value(.templates, [])
    themes = try c.value(.themes, [])
    placements = try c.value(.placements, [])
    experiments = try c.value(.experiments, [])
    assets = try c.value(.assets, [])
    imageProxy = try c.decodeIfPresent(ImageProxy.self, forKey: .imageProxy)
    provenance = try c.value(.provenance, [:])
  }

  /// The newest minor of `ref` (`id@major`).
  public func contract(_ ref: String) -> Contract? {
    guard let (id, major) = ContractRef.parse(ref) else { return nil }
    return contracts.filter { $0.id == id && $0.major == major }.max { $0.minor < $1.minor }
  }

  public func design(_ id: String) -> Design? { designs.first { $0.id == id } }
  public func templates(for designId: String) -> [TemplateEntry] { templates.filter { $0.designId == designId } }
  public func asset(_ sha256: String) -> AssetEntry? { assets.first { $0.sha256 == sha256 } }
  public func assets(for template: TemplateEntry) -> [AssetEntry] { template.assets.compactMap(asset) }
}

public struct ManifestSignature: Codable, Equatable, Sendable {
  public var alg: String
  public var keyId: String
  public var signature: String

  public init(alg: String = "Ed25519", keyId: String, signature: String) {
    self.alg = alg; self.keyId = keyId; self.signature = signature
  }

  enum CodingKeys: String, CodingKey { case alg, keyId, signature }
  public init(from d: Decoder) throws {
    let c = try d.container(keyedBy: CodingKeys.self)
    alg = try c.value(.alg, "Ed25519")
    keyId = try c.decode(String.self, forKey: .keyId)
    signature = try c.decode(String.self, forKey: .signature)
  }
}

public enum ContractRef {
  public static func parse(_ ref: String) -> (String, Int)? {
    guard let at = ref.lastIndex(of: "@"), at > ref.startIndex, let major = Int(ref[ref.index(after: at)...]) else { return nil }
    return (String(ref[..<at]), major)
  }
}

public enum InstallPolicy: String, Codable, Sendable { case always = "Always", onRequest = "OnRequest", onDemand = "OnDemand" }

public struct Design: Codable, Equatable, Sendable {
  public static let defaultVariant = "default"
  public var id: String
  public var contract: String
  public var title: String
  public var description: String?
  public var author: String?
  public var variant: String
  public var tags: [String: String]
  public var previews: [Preview]
  public var forkedFrom: String?
  public var revision: String?
  public var revoked: Bool
  public var install: InstallPolicy

  enum CodingKeys: String, CodingKey { case id, contract, title, description, author, variant, tags, previews, forkedFrom, revision, revoked, install }
  public init(from d: Decoder) throws {
    let c = try d.container(keyedBy: CodingKeys.self)
    id = try c.decode(String.self, forKey: .id)
    contract = try c.decode(String.self, forKey: .contract)
    title = try c.decode(String.self, forKey: .title)
    description = try c.decodeIfPresent(String.self, forKey: .description)
    author = try c.decodeIfPresent(String.self, forKey: .author)
    variant = try c.value(.variant, Design.defaultVariant)
    tags = try c.value(.tags, [:])
    previews = try c.value(.previews, [])
    forkedFrom = try c.decodeIfPresent(String.self, forKey: .forkedFrom)
    revision = try c.decodeIfPresent(String.self, forKey: .revision)
    revoked = try c.value(.revoked, false)
    install = try c.value(.install, .onDemand)
  }
}

public struct TemplateEntry: Codable, Equatable, Sendable {
  public var sha256: String
  public var bytes: Int64
  public var designId: String
  public var contract: String
  public var target: Target
  public var tags: [String: String]
  public var previews: [Preview]
  public var revoked: Bool
  public var reads: [String]
  /// The lowest contract minor an app must implement to play it (§5.1).
  public var requiresMinor: Int
  public var emits: [String]
  public var assets: [String]
  public var fonts: [String]
  /// Where the document came from (tool, design, revision, commit); never read for decisions.
  public var source: [String: String]

  enum CodingKeys: String, CodingKey { case sha256, bytes, designId, contract, target, tags, previews, revoked, reads, requiresMinor, emits, assets, fonts, source }
  public init(from d: Decoder) throws {
    let c = try d.container(keyedBy: CodingKeys.self)
    sha256 = try c.decode(String.self, forKey: .sha256)
    bytes = try c.decode(Int64.self, forKey: .bytes)
    designId = try c.decode(String.self, forKey: .designId)
    contract = try c.decode(String.self, forKey: .contract)
    target = try c.decode(Target.self, forKey: .target)
    tags = try c.value(.tags, [:])
    previews = try c.value(.previews, [])
    revoked = try c.value(.revoked, false)
    reads = try c.value(.reads, [])
    requiresMinor = try c.value(.requiresMinor, 0)
    emits = try c.value(.emits, [])
    assets = try c.value(.assets, [])
    fonts = try c.value(.fonts, [])
    source = try c.value(.source, [:])
  }
}

public enum Surfaces {
  public static let inApp = "in-app"
  public static let launcherWidget = "launcher-widget"
  public static let wearWidget = "wear-widget"
  public static let notification = "notification"
  public static let wearApp = "wear-app"
  public static let web = "web"
  public static let desktop = "desktop"
  public static let tv = "tv"
  public static let snapshot = "snapshot"
}

public struct Target: Codable, Equatable, Hashable, Sendable {
  public static let themeHost = "host"
  public var surface: String
  public var rcApiLevel: Int
  public var profiles: [String]
  public var size: SizeRange
  public var theme: String
  public var container: String?

  public init(surface: String, rcApiLevel: Int, profiles: [String] = [], size: SizeRange = SizeRange(), theme: String = Target.themeHost, container: String? = nil) {
    self.surface = surface; self.rcApiLevel = rcApiLevel; self.profiles = profiles; self.size = size; self.theme = theme; self.container = container
  }

  enum CodingKeys: String, CodingKey { case surface, rcApiLevel, profiles, size, theme, container }
  public init(from d: Decoder) throws {
    let c = try d.container(keyedBy: CodingKeys.self)
    surface = try c.decode(String.self, forKey: .surface)
    rcApiLevel = try c.decode(Int.self, forKey: .rcApiLevel)
    profiles = try c.value(.profiles, [])
    size = try c.value(.size, SizeRange())
    theme = try c.value(.theme, Target.themeHost)
    container = try c.decodeIfPresent(String.self, forKey: .container)
  }

  /// Surface, level, profiles, container and size (docs/PROTOCOL.md §5.1); the theme is [themeFits].
  public func fits(_ caps: PlayerCapabilities, widthDp: Int? = nil, heightDp: Int? = nil) -> Bool {
    surface == caps.surface && rcApiLevel <= caps.maxRcApiLevel && Set(profiles).isSubset(of: caps.profiles)
      && container == caps.container && size.covers(widthDp: widthDp, heightDp: heightDp)
  }

  public func themeFits(_ caps: PlayerCapabilities, wanted: String?) -> Bool {
    if theme == Target.themeHost { return caps.themes.contains(Target.themeHost) }
    if let wanted { return theme == wanted }
    return caps.themes.contains(theme)
  }
}

public struct SizeRange: Codable, Equatable, Hashable, Sendable {
  public var minWidthDp: Int?
  public var maxWidthDp: Int?
  public var minHeightDp: Int?
  public var maxHeightDp: Int?

  public init(minWidthDp: Int? = nil, maxWidthDp: Int? = nil, minHeightDp: Int? = nil, maxHeightDp: Int? = nil) {
    self.minWidthDp = minWidthDp; self.maxWidthDp = maxWidthDp; self.minHeightDp = minHeightDp; self.maxHeightDp = maxHeightDp
  }

  public func covers(widthDp: Int?, heightDp: Int?) -> Bool {
    func within(_ v: Int?, _ lo: Int?, _ hi: Int?) -> Bool {
      guard let v else { return true }
      return (lo ?? Int.min) <= v && v <= (hi ?? Int.max)
    }
    return within(widthDp, minWidthDp, maxWidthDp) && within(heightDp, minHeightDp, maxHeightDp)
  }

  /// The tie-break span of §5.2.
  var span: Int64 {
    func w(_ lo: Int?, _ hi: Int?) -> Int64 { Int64(hi ?? 100_000) - Int64(lo ?? 0) }
    return w(minWidthDp, maxWidthDp) * w(minHeightDp, maxHeightDp)
  }
}

public struct PlayerCapabilities: Codable, Equatable, Sendable {
  public var surface: String
  public var maxRcApiLevel: Int
  public var profiles: Set<String>
  public var livePush: Bool
  public var themes: Set<String>
  public var container: String?
  public var maxBytes: Int64

  public init(surface: String, maxRcApiLevel: Int, profiles: Set<String>, livePush: Bool = true, themes: Set<String> = [Target.themeHost], container: String? = nil, maxBytes: Int64 = .max) {
    self.surface = surface; self.maxRcApiLevel = maxRcApiLevel; self.profiles = profiles; self.livePush = livePush
    self.themes = themes; self.container = container; self.maxBytes = maxBytes
  }

  enum CodingKeys: String, CodingKey { case surface, maxRcApiLevel, profiles, livePush, themes, container, maxBytes }
  public init(from d: Decoder) throws {
    let c = try d.container(keyedBy: CodingKeys.self)
    surface = try c.decode(String.self, forKey: .surface)
    maxRcApiLevel = try c.decode(Int.self, forKey: .maxRcApiLevel)
    profiles = try c.decode(Set<String>.self, forKey: .profiles)
    livePush = try c.value(.livePush, true)
    themes = try c.value(.themes, [Target.themeHost])
    container = try c.decodeIfPresent(String.self, forKey: .container)
    maxBytes = try c.value(.maxBytes, Int64.max)
  }
}

public struct Preview: Codable, Equatable, Sendable {
  public var sha256: String
  public var mime: String
  public var widthPx: Int
  public var heightPx: Int
  public var theme: String?
  public var example: String?
  public var surface: String?

  public var fileName: String {
    let ext = mime.split(separator: "/").last.map { String($0.split(separator: "+").first ?? $0) } ?? "bin"
    return "\(sha256).\(ext)"
  }

  enum CodingKeys: String, CodingKey { case sha256, mime, widthPx, heightPx, theme, example, surface }
  public init(from d: Decoder) throws {
    let c = try d.container(keyedBy: CodingKeys.self)
    sha256 = try c.decode(String.self, forKey: .sha256)
    mime = try c.value(.mime, "image/webp")
    widthPx = try c.decode(Int.self, forKey: .widthPx)
    heightPx = try c.decode(Int.self, forKey: .heightPx)
    theme = try c.decodeIfPresent(String.self, forKey: .theme)
    example = try c.decodeIfPresent(String.self, forKey: .example)
    surface = try c.decodeIfPresent(String.self, forKey: .surface)
  }
}

public struct ThemePack: Codable, Equatable, Sendable {
  public var id: String
  public var title: String
  public var mode: String
  public var values: [String: NamedValue]
  public var previews: [Preview]

  enum CodingKeys: String, CodingKey { case id, title, mode, values, previews }
  public init(from d: Decoder) throws {
    let c = try d.container(keyedBy: CodingKeys.self)
    id = try c.decode(String.self, forKey: .id)
    title = try c.decode(String.self, forKey: .title)
    mode = try c.value(.mode, "light")
    values = try c.decode([String: NamedValue].self, forKey: .values)
    previews = try c.value(.previews, [])
  }
}

// MARK: - Contracts

public enum ValueType: String, Codable, Sendable { case string, int, long, float, bool, color, image }
public enum DataSource: String, Codable, Sendable { case app = "App", decision = "Decision", feed = "Feed" }

public struct Contract: Codable, Equatable, Sendable {
  public var id: String
  public var major: Int
  public var minor: Int
  public var title: String?
  public var inputs: [String: InputSpec]
  public var lists: [String: ListSpec]
  public var actions: [String: ActionSpec]
  public var examples: [String: [String: JSONValue]]
  public var dataSource: DataSource
  public var ref: String { "\(id)@\(major)" }

  enum CodingKeys: String, CodingKey { case id, major, minor, title, inputs, lists, actions, examples, dataSource }
  public init(from d: Decoder) throws {
    let c = try d.container(keyedBy: CodingKeys.self)
    id = try c.decode(String.self, forKey: .id)
    major = try c.decode(Int.self, forKey: .major)
    minor = try c.value(.minor, 0)
    title = try c.decodeIfPresent(String.self, forKey: .title)
    inputs = try c.value(.inputs, [:])
    lists = try c.value(.lists, [:])
    actions = try c.value(.actions, [:])
    examples = try c.value(.examples, [:])
    dataSource = try c.value(.dataSource, .app)
  }
}

public struct InputSpec: Codable, Equatable, Sendable {
  public var type: ValueType
  public var required: Bool
  public var `default`: JSONValue?
  public var maxLength: Int?
  public var min: Double?
  public var max: Double?
  public var role: String?
  public var description: String?
  /// The contract minor that added this input.
  public var since: Int

  enum CodingKeys: String, CodingKey { case type, required, `default`, maxLength, min, max, role, description, since }
  public init(from d: Decoder) throws {
    let c = try d.container(keyedBy: CodingKeys.self)
    type = try c.decode(ValueType.self, forKey: .type)
    required = try c.value(.required, false)
    `default` = try c.decodeIfPresent(JSONValue.self, forKey: .default)
    maxLength = try c.decodeIfPresent(Int.self, forKey: .maxLength)
    min = try c.decodeIfPresent(Double.self, forKey: .min)
    max = try c.decodeIfPresent(Double.self, forKey: .max)
    role = try c.decodeIfPresent(String.self, forKey: .role)
    description = try c.decodeIfPresent(String.self, forKey: .description)
    since = try c.value(.since, 0)
  }
}

public struct ListSpec: Codable, Equatable, Sendable {
  public var item: [String: InputSpec]
  public var max: Int
  public var description: String?
  /// The contract minor that added this list.
  public var since: Int

  enum CodingKeys: String, CodingKey { case item, max, description, since }
  public init(from d: Decoder) throws {
    let c = try d.container(keyedBy: CodingKeys.self)
    item = try c.decode([String: InputSpec].self, forKey: .item)
    max = try c.decode(Int.self, forKey: .max)
    description = try c.decodeIfPresent(String.self, forKey: .description)
    since = try c.value(.since, 0)
  }
}

public struct ActionSpec: Codable, Equatable, Sendable {
  public var kind: String
  public var args: [String: InputSpec]
  public var description: String?
  /// The contract minor that added this action.
  public var since: Int

  enum CodingKeys: String, CodingKey { case kind, args, description, since }
  public init(from d: Decoder) throws {
    let c = try d.container(keyedBy: CodingKeys.self)
    kind = try c.decode(String.self, forKey: .kind)
    args = try c.value(.args, [:])
    description = try c.decodeIfPresent(String.self, forKey: .description)
    since = try c.value(.since, 0)
  }
}

public enum ActionKinds {
  public static let openURL = "open-url"
  public static let navigate = "navigate"
  public static let dismiss = "dismiss"
  public static let track = "track"
  public static let refresh = "refresh"
}

/// A typed named value; JSON `{"type": "<kind>", …}` as kotlinx.serialization writes it.
public enum NamedValue: Codable, Equatable, Sendable {
  case string(String)
  case int(Int32)
  case long(Int64)
  case float(Float)
  case bool(Bool)
  case color(argb: Int64)
  case image(ref: String)

  enum CodingKeys: String, CodingKey { case type, value, argb, ref }
  public init(from d: Decoder) throws {
    let c = try d.container(keyedBy: CodingKeys.self)
    switch try c.decode(String.self, forKey: .type) {
    case "string": self = .string(try c.decode(String.self, forKey: .value))
    case "int": self = .int(try c.decode(Int32.self, forKey: .value))
    case "long": self = .long(try c.decode(Int64.self, forKey: .value))
    case "float": self = .float(try c.decode(Float.self, forKey: .value))
    case "bool": self = .bool(try c.decode(Bool.self, forKey: .value))
    case "color": self = .color(argb: try c.decode(Int64.self, forKey: .argb))
    case "image": self = .image(ref: try c.decode(String.self, forKey: .ref))
    case let t: throw DecodingError.dataCorruptedError(forKey: .type, in: c, debugDescription: "unknown named value type \(t)")
    }
  }

  public func encode(to e: Encoder) throws {
    var c = e.container(keyedBy: CodingKeys.self)
    switch self {
    case .string(let v): try c.encode("string", forKey: .type); try c.encode(v, forKey: .value)
    case .int(let v): try c.encode("int", forKey: .type); try c.encode(v, forKey: .value)
    case .long(let v): try c.encode("long", forKey: .type); try c.encode(v, forKey: .value)
    case .float(let v): try c.encode("float", forKey: .type); try c.encode(v, forKey: .value)
    case .bool(let v): try c.encode("bool", forKey: .type); try c.encode(v, forKey: .value)
    case .color(let v): try c.encode("color", forKey: .type); try c.encode(v, forKey: .argb)
    case .image(let v): try c.encode("image", forKey: .type); try c.encode(v, forKey: .ref)
    }
  }
}

// MARK: - Resources

public enum AssetKind: String, Codable, Sendable { case image, font }

public struct FontFace: Codable, Equatable, Hashable, Sendable {
  public var family: String
  public var weight: Int
  public var italic: Bool

  public init(family: String, weight: Int = 400, italic: Bool = false) { self.family = family; self.weight = weight; self.italic = italic }

  enum CodingKeys: String, CodingKey { case family, weight, italic }
  public init(from d: Decoder) throws {
    let c = try d.container(keyedBy: CodingKeys.self)
    family = try c.decode(String.self, forKey: .family)
    weight = try c.value(.weight, 400)
    italic = try c.value(.italic, false)
  }
}

public struct AssetEntry: Codable, Equatable, Sendable {
  public var sha256: String
  public var bytes: Int64
  public var mime: String
  public var kind: AssetKind
  public var name: String?
  public var font: FontFace?
  public var license: String?

  public var fileName: String {
    let ext: String
    switch mime {
    case "font/ttf": ext = "ttf"
    case "font/otf": ext = "otf"
    case "font/woff2": ext = "woff2"
    case "image/svg+xml": ext = "svg"
    default: ext = String(mime.split(separator: "/").last ?? "bin")
    }
    return "\(sha256).\(ext)"
  }
}

public struct ImageProxy: Codable, Equatable, Sendable {
  public var urlTemplate: String
  public var sourceHosts: [String]
  public var formats: [String]
  public var maxWidthPx: Int
  public var maxHeightPx: Int

  public init(urlTemplate: String, sourceHosts: [String] = [], formats: [String] = ["webp", "png"], maxWidthPx: Int = 2048, maxHeightPx: Int = 2048) {
    self.urlTemplate = urlTemplate; self.sourceHosts = sourceHosts; self.formats = formats; self.maxWidthPx = maxWidthPx; self.maxHeightPx = maxHeightPx
  }

  enum CodingKeys: String, CodingKey { case urlTemplate, sourceHosts, formats, maxWidthPx, maxHeightPx }
  public init(from d: Decoder) throws {
    let c = try d.container(keyedBy: CodingKeys.self)
    urlTemplate = try c.decode(String.self, forKey: .urlTemplate)
    sourceHosts = try c.value(.sourceHosts, [])
    formats = try c.value(.formats, ["webp", "png"])
    maxWidthPx = try c.value(.maxWidthPx, 2048)
    maxHeightPx = try c.value(.maxHeightPx, 2048)
  }
}

// MARK: - Decisions, feeds, events

public struct TemplateRef: Codable, Equatable, Sendable {
  public var designId: String?
  public var sha256: String?
  public var contract: String?
  public init(designId: String? = nil, sha256: String? = nil, contract: String? = nil) {
    self.designId = designId; self.sha256 = sha256; self.contract = contract
  }
}

public struct Tracking: Codable, Equatable, Sendable {
  public var impression: [String]
  public var viewable: [String]
  public var click: [String]
  public var dismiss: [String]

  public init() { impression = []; viewable = []; click = []; dismiss = [] }
  enum CodingKeys: String, CodingKey { case impression, viewable, click, dismiss }
  public init(from d: Decoder) throws {
    let c = try d.container(keyedBy: CodingKeys.self)
    impression = try c.value(.impression, []); viewable = try c.value(.viewable, [])
    click = try c.value(.click, []); dismiss = try c.value(.dismiss, [])
  }
}

public struct Decision: Codable, Equatable, Sendable {
  public var placement: String
  public var decisionId: String
  public var template: TemplateRef
  public var data: [String: JSONValue]
  public var ttlSeconds: Int64
  public var tracking: Tracking
  public var experiments: [String: String]

  public init(placement: String, decisionId: String, template: TemplateRef, data: [String: JSONValue] = [:], ttlSeconds: Int64 = 300, tracking: Tracking = Tracking(), experiments: [String: String] = [:]) {
    self.placement = placement; self.decisionId = decisionId; self.template = template; self.data = data
    self.ttlSeconds = ttlSeconds; self.tracking = tracking; self.experiments = experiments
  }

  enum CodingKeys: String, CodingKey { case placement, decisionId, template, data, ttlSeconds, tracking, experiments }
  public init(from d: Decoder) throws {
    let c = try d.container(keyedBy: CodingKeys.self)
    placement = try c.decode(String.self, forKey: .placement)
    decisionId = try c.decode(String.self, forKey: .decisionId)
    template = try c.decode(TemplateRef.self, forKey: .template)
    data = try c.value(.data, [:])
    ttlSeconds = try c.value(.ttlSeconds, 300)
    tracking = try c.value(.tracking, Tracking())
    experiments = try c.value(.experiments, [:])
  }
}

public struct Conditions: Codable, Equatable, Sendable {
  public var platforms: Set<String>
  public var locales: Set<String>
  public var minAppVersion: Int64?
  public var maxAppVersion: Int64?
  public var segments: Set<String>
  public var excludeSegments: Set<String>

  public init() { platforms = []; locales = []; segments = []; excludeSegments = [] }
  enum CodingKeys: String, CodingKey { case platforms, locales, minAppVersion, maxAppVersion, segments, excludeSegments }
  public init(from d: Decoder) throws {
    let c = try d.container(keyedBy: CodingKeys.self)
    platforms = try c.value(.platforms, []); locales = try c.value(.locales, [])
    minAppVersion = try c.decodeIfPresent(Int64.self, forKey: .minAppVersion)
    maxAppVersion = try c.decodeIfPresent(Int64.self, forKey: .maxAppVersion)
    segments = try c.value(.segments, []); excludeSegments = try c.value(.excludeSegments, [])
  }
}

public struct Rollout: Codable, Equatable, Sendable {
  public var experiment: String
  public var arm: String
  public var fromBasisPoints: Int
  public var toBasisPoints: Int

  enum CodingKeys: String, CodingKey { case experiment, arm, fromBasisPoints, toBasisPoints }
  public init(from d: Decoder) throws {
    let c = try d.container(keyedBy: CodingKeys.self)
    experiment = try c.decode(String.self, forKey: .experiment)
    arm = try c.decode(String.self, forKey: .arm)
    fromBasisPoints = try c.value(.fromBasisPoints, 0)
    toBasisPoints = try c.decode(Int.self, forKey: .toBasisPoints)
  }
}

public struct FrequencyCap: Codable, Equatable, Sendable {
  public var maxImpressions: Int
  public var perSeconds: Int64
}

public struct PlacementRule: Codable, Equatable, Sendable {
  public var id: String
  public var placement: String
  public var template: TemplateRef
  public var data: [String: JSONValue]
  public var priority: Int
  public var startEpochMs: Int64?
  public var endEpochMs: Int64?
  public var conditions: Conditions
  public var rollout: Rollout?
  public var frequency: FrequencyCap?
  public var tracking: Tracking
  /// Only for users assigned this arm of a manifest experiment (§8.1).
  public var arm: ArmRef?

  enum CodingKeys: String, CodingKey { case id, placement, template, data, priority, startEpochMs, endEpochMs, conditions, rollout, frequency, tracking, arm }
  public init(from d: Decoder) throws {
    let c = try d.container(keyedBy: CodingKeys.self)
    id = try c.decode(String.self, forKey: .id)
    placement = try c.decode(String.self, forKey: .placement)
    template = try c.decode(TemplateRef.self, forKey: .template)
    data = try c.value(.data, [:])
    priority = try c.value(.priority, 0)
    startEpochMs = try c.decodeIfPresent(Int64.self, forKey: .startEpochMs)
    endEpochMs = try c.decodeIfPresent(Int64.self, forKey: .endEpochMs)
    conditions = try c.value(.conditions, Conditions())
    rollout = try c.decodeIfPresent(Rollout.self, forKey: .rollout)
    frequency = try c.decodeIfPresent(FrequencyCap.self, forKey: .frequency)
    tracking = try c.value(.tracking, Tracking())
    arm = try c.decodeIfPresent(ArmRef.self, forKey: .arm)
  }
}

/// An A/B test (§8.1): users matching `conditions` during the schedule are enrolled at
/// `allocationBasisPoints`, each in one sticky arm in proportion to the arms' weights.
public struct Experiment: Codable, Equatable, Sendable {
  public var id: String
  public var arms: [Arm]
  public var startEpochMs: Int64?
  public var endEpochMs: Int64?
  public var conditions: Conditions
  public var allocationBasisPoints: Int

  public init(id: String, arms: [Arm], startEpochMs: Int64? = nil, endEpochMs: Int64? = nil, conditions: Conditions = Conditions(), allocationBasisPoints: Int = 10_000) {
    self.id = id; self.arms = arms; self.startEpochMs = startEpochMs; self.endEpochMs = endEpochMs
    self.conditions = conditions; self.allocationBasisPoints = allocationBasisPoints
  }

  enum CodingKeys: String, CodingKey { case id, arms, startEpochMs, endEpochMs, conditions, allocationBasisPoints }
  public init(from d: Decoder) throws {
    let c = try d.container(keyedBy: CodingKeys.self)
    id = try c.decode(String.self, forKey: .id)
    arms = try c.decode([Arm].self, forKey: .arms)
    startEpochMs = try c.decodeIfPresent(Int64.self, forKey: .startEpochMs)
    endEpochMs = try c.decodeIfPresent(Int64.self, forKey: .endEpochMs)
    conditions = try c.value(.conditions, Conditions())
    allocationBasisPoints = try c.value(.allocationBasisPoints, 10_000)
  }
}

/// One arm: its weight among the experiment's arms, and the design it picks per contract (`id@major` → design id).
public struct Arm: Codable, Equatable, Sendable {
  public var id: String
  public var weight: Int
  public var designs: [String: String]

  public init(id: String, weight: Int = 1, designs: [String: String] = [:]) { self.id = id; self.weight = weight; self.designs = designs }

  enum CodingKeys: String, CodingKey { case id, weight, designs }
  public init(from d: Decoder) throws {
    let c = try d.container(keyedBy: CodingKeys.self)
    id = try c.decode(String.self, forKey: .id)
    weight = try c.value(.weight, 1)
    designs = try c.value(.designs, [:])
  }
}

public struct ArmRef: Codable, Equatable, Sendable {
  public var experiment: String
  public var arm: String
}

public struct FeedItem: Codable, Equatable, Sendable {
  public var id: String
  public var template: TemplateRef
  public var data: [String: JSONValue]
  public var tracking: Tracking

  enum CodingKeys: String, CodingKey { case id, template, data, tracking }
  public init(from d: Decoder) throws {
    let c = try d.container(keyedBy: CodingKeys.self)
    id = try c.decode(String.self, forKey: .id)
    template = try c.decode(TemplateRef.self, forKey: .template)
    data = try c.value(.data, [:])
    tracking = try c.value(.tracking, Tracking())
  }
}

public struct FeedPage: Codable, Equatable, Sendable {
  public var feed: String
  public var items: [FeedItem]
  public var next: String?
  public var ttlSeconds: Int64

  enum CodingKeys: String, CodingKey { case feed, items, next, ttlSeconds }
  public init(from d: Decoder) throws {
    let c = try d.container(keyedBy: CodingKeys.self)
    feed = try c.decode(String.self, forKey: .feed)
    items = try c.decode([FeedItem].self, forKey: .items)
    next = try c.decodeIfPresent(String.self, forKey: .next)
    ttlSeconds = try c.value(.ttlSeconds, 60)
  }
}

public struct TemplateEvent: Codable, Equatable, Sendable {
  public enum Kind: String, Codable, Sendable { case impression = "Impression", viewable = "Viewable", action = "Action", dismiss = "Dismiss", fallback = "Fallback", error = "Error", exposure = "Exposure", leave = "Leave" }
  public var type: Kind
  public var atEpochMs: Int64
  public var placement: String?
  public var decisionId: String?
  public var feedItemId: String?
  public var designId: String?
  public var sha256: String?
  public var action: String?
  public var detail: String?
  /// Experiment → arm for every experiment that shaped what drew.
  public var experiments: [String: String]
  /// An action's arguments that its contract declares (§7): never any other.
  public var args: [String: String]
  /// A `leave`'s time on screen: milliseconds at least half visible while it was shown.
  public var durationMs: Int64?

  public init(type: Kind, atEpochMs: Int64, placement: String? = nil, decisionId: String? = nil, feedItemId: String? = nil, designId: String? = nil, sha256: String? = nil, action: String? = nil, detail: String? = nil, experiments: [String: String] = [:], args: [String: String] = [:], durationMs: Int64? = nil) {
    self.type = type; self.atEpochMs = atEpochMs; self.placement = placement; self.decisionId = decisionId
    self.feedItemId = feedItemId; self.designId = designId; self.sha256 = sha256; self.action = action; self.detail = detail
    self.experiments = experiments; self.args = args; self.durationMs = durationMs
  }

  enum CodingKeys: String, CodingKey { case type, atEpochMs, placement, decisionId, feedItemId, designId, sha256, action, detail, experiments, args, durationMs }
  public init(from d: Decoder) throws {
    let c = try d.container(keyedBy: CodingKeys.self)
    type = try c.decode(Kind.self, forKey: .type)
    atEpochMs = try c.decode(Int64.self, forKey: .atEpochMs)
    placement = try c.decodeIfPresent(String.self, forKey: .placement)
    decisionId = try c.decodeIfPresent(String.self, forKey: .decisionId)
    feedItemId = try c.decodeIfPresent(String.self, forKey: .feedItemId)
    designId = try c.decodeIfPresent(String.self, forKey: .designId)
    sha256 = try c.decodeIfPresent(String.self, forKey: .sha256)
    action = try c.decodeIfPresent(String.self, forKey: .action)
    detail = try c.decodeIfPresent(String.self, forKey: .detail)
    experiments = try c.value(.experiments, [:])
    args = try c.value(.args, [:])
    durationMs = try c.decodeIfPresent(Int64.self, forKey: .durationMs)
  }
}
