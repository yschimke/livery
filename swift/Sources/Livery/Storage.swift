import Foundation

public enum FetchResult: Sendable, Equatable {
  case ok(Data, etag: String?)
  case notModified
  case failed(status: Int?, message: String, retryAfterSeconds: Int64? = nil)
}

/// GET with conditional revalidation.
public protocol Fetcher: Sendable {
  func get(_ url: String, etag: String?) async -> FetchResult
}

/// `URLSession`, the platform's own HTTP stack (HTTP/2, the system proxy and trust settings,
/// background-friendly). `file:` URLs and `asset://<path>` (from `assets`) are read locally.
public struct URLSessionFetcher: Fetcher {
  public let session: URLSession
  public let assets: (@Sendable (String) -> Data?)?

  public init(session: URLSession = URLSessionFetcher.defaultSession, assets: (@Sendable (String) -> Data?)? = nil) {
    self.session = session; self.assets = assets
  }

  public static let defaultSession: URLSession = {
    let c = URLSessionConfiguration.default
    c.timeoutIntervalForRequest = 30
    c.timeoutIntervalForResource = 60
    c.requestCachePolicy = .reloadIgnoringLocalCacheData // Livery revalidates with its own ETag
    return URLSession(configuration: c)
  }()

  public func get(_ url: String, etag: String?) async -> FetchResult {
    if url.hasPrefix("asset://") {
      return assets?(String(url.dropFirst(8))).map { .ok($0, etag: nil) } ?? .failed(status: 404, message: "no asset \(url)")
    }
    guard let u = URL(string: url) else { return .failed(status: nil, message: "bad url \(url)") }
    if u.isFileURL {
      return (try? Data(contentsOf: u)).map { .ok($0, etag: nil) } ?? .failed(status: 404, message: "missing \(url)")
    }
    var req = URLRequest(url: u)
    if let etag { req.setValue(etag, forHTTPHeaderField: "If-None-Match") }
    do {
      let (data, response) = try await session.data(for: req)
      guard let http = response as? HTTPURLResponse else { return .failed(status: nil, message: "not HTTP") }
      let retry = http.value(forHTTPHeaderField: "Retry-After").flatMap { Int64($0) }
      switch http.statusCode {
      case 200: return .ok(data, etag: http.value(forHTTPHeaderField: "ETag"))
      case 304: return .notModified
      default: return .failed(status: http.statusCode, message: "HTTP \(http.statusCode)", retryAfterSeconds: retry)
      }
    } catch {
      return .failed(status: nil, message: error.localizedDescription)
    }
  }
}

/// Content-addressed bytes (blobs, assets); keys are SHA-256 hex.
public protocol BlobStore: Sendable {
  func get(_ sha256: String) async -> Data?
  func put(_ sha256: String, _ data: Data) async
  func delete(_ sha256: String) async
  func keys() async -> Set<String>
}

/// Small named documents (the last good manifest, metadata, installed designs).
public protocol KeyValueStore: Sendable {
  func get(_ key: String) async -> String?
  func put(_ key: String, _ value: String) async
  func remove(_ key: String) async
}

public actor MemoryBlobStore: BlobStore {
  private var map: [String: Data] = [:]
  public init() {}
  public func get(_ sha256: String) -> Data? { map[sha256] }
  public func put(_ sha256: String, _ data: Data) { map[sha256] = data }
  public func delete(_ sha256: String) { map[sha256] = nil }
  public func keys() -> Set<String> { Set(map.keys) }
}

public actor MemoryKeyValueStore: KeyValueStore {
  private var map: [String: String] = [:]
  public init() {}
  public func get(_ key: String) -> String? { map[key] }
  public func put(_ key: String, _ value: String) { map[key] = value }
  public func remove(_ key: String) { map[key] = nil }
}

/// Blobs and assets as files named by hash in `directory` (Caches), written atomically.
public struct FileBlobStore: BlobStore {
  public let directory: URL
  public init(directory: URL) {
    self.directory = directory
    try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
  }

  private func file(_ sha: String) -> URL { directory.appendingPathComponent("\(sha).rc") }
  public func get(_ sha256: String) async -> Data? { try? Data(contentsOf: file(sha256)) }
  public func put(_ sha256: String, _ data: Data) async { try? data.write(to: file(sha256), options: .atomic) }
  public func delete(_ sha256: String) async { try? FileManager.default.removeItem(at: file(sha256)) }
  public func keys() async -> Set<String> {
    let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
    return Set(names.filter { $0.hasSuffix(".rc") }.map { String($0.dropLast(3)) })
  }
}

/// Small documents in `directory` (Application Support), each named by the SHA-256 of its whole
/// key so keys never collide, written atomically.
public struct FileKeyValueStore: KeyValueStore {
  public let directory: URL
  public init(directory: URL) {
    self.directory = directory
    try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
  }

  private func file(_ key: String) -> URL { directory.appendingPathComponent(Hashing.sha256Hex(Data(key.utf8))) }
  public func get(_ key: String) async -> String? { try? String(contentsOf: file(key), encoding: .utf8) }
  public func put(_ key: String, _ value: String) async { try? Data(value.utf8).write(to: file(key), options: .atomic) }
  public func remove(_ key: String) async { try? FileManager.default.removeItem(at: file(key)) }
}
