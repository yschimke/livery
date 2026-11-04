import Foundation

/// docs/PROTOCOL.md §6.1.
public enum ImageRef: Equatable, Sendable {
  case asset(sha256: String)
  case remote(url: String)
  case app(key: String)

  public static func parse(_ value: String) -> ImageRef? {
    if value.hasPrefix("asset:") {
      let sha = String(value.dropFirst(6))
      return sha.count == 64 && sha.allSatisfy({ ("0"..."9").contains($0) || ("a"..."f").contains($0) }) ? .asset(sha256: sha) : nil
    }
    if value.hasPrefix("https://") && value.count > 8 { return .remote(url: value) }
    if value.hasPrefix("app:") && value.count > 4 { return .app(key: String(value.dropFirst(4))) }
    return nil
  }
}

public enum ResourceURLs {
  /// docs/PROTOCOL.md §1: absolute as is, `/` replaces the path, else replaces the last segment.
  public static func resolve(_ base: String, _ ref: String) -> String {
    if ref.contains("://") { return ref }
    if ref.hasPrefix("/") {
      let scheme = base.components(separatedBy: "://").first ?? base
      let afterScheme = base.range(of: "://").map { String(base[$0.upperBound...]) } ?? base
      let host = afterScheme.split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? afterScheme
      return "\(scheme)://\(host)\(ref)"
    }
    let dir = base.range(of: "/", options: .backwards).map { String(base[..<$0.lowerBound]) } ?? ""
    return dir + "/" + ref
  }

  /// docs/PROTOCOL.md §6.2: the URL to fetch, or nil when neither the proxy nor the allowlist permits it.
  public static func remote(proxy: ImageProxy?, directHosts: Set<String>, url: String, widthPx: Int, heightPx: Int) -> String? {
    guard let host = remoteHost(url) else { return nil }
    func matches(_ h: String) -> Bool { host == h || host.hasSuffix("." + h) }
    if let proxy {
      if !proxy.sourceHosts.isEmpty && !proxy.sourceHosts.contains(where: matches) { return nil }
      return proxy.urlTemplate
        .replacingOccurrences(of: "{url}", with: percentEncode(url))
        .replacingOccurrences(of: "{width}", with: String(min(max(widthPx, 1), proxy.maxWidthPx)))
        .replacingOccurrences(of: "{height}", with: String(min(max(heightPx, 1), proxy.maxHeightPx)))
        .replacingOccurrences(of: "{format}", with: proxy.formats.first ?? "webp")
    }
    return directHosts.contains(where: matches) ? url : nil
  }

  /// The host an `https` URL really reaches (PROTOCOL.md §6.2): the authority runs to the first
  /// `/`, `?`, `#` or `\`; user information or a backslash refuses the URL; IPv6 keeps its brackets.
  public static func remoteHost(_ url: String) -> String? {
    guard url.hasPrefix("https://") else { return nil }
    let rest = Array(url.dropFirst(8))
    let end = rest.firstIndex { $0 == "/" || $0 == "?" || $0 == "#" || $0 == "\\" }
    let authority = String(end.map { rest[..<$0] } ?? rest[...])
    if authority.contains("@") { return nil }
    if let end, rest[end] == "\\" { return nil }
    let host: String
    if authority.hasPrefix("[") {
      guard let close = authority.firstIndex(of: "]") else { return nil }
      host = String(authority[...close])
    } else {
      host = String(authority.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
    }
    return host.isEmpty ? nil : host.lowercased()
  }

  /// RFC 3986: everything but `A–Z a–z 0–9 - . _ ~`, as uppercase `%XX` of the UTF-8 bytes.
  public static func percentEncode(_ s: String) -> String {
    let hex = Array("0123456789ABCDEF")
    var out = ""
    for b in Array(s.utf8) {
      let c = Character(UnicodeScalar(b))
      if (b >= 0x41 && b <= 0x5A) || (b >= 0x61 && b <= 0x7A) || (b >= 0x30 && b <= 0x39) || c == "-" || c == "." || c == "_" || c == "~" {
        out.append(c)
      } else {
        out.append("%"); out.append(hex[Int(b >> 4)]); out.append(hex[Int(b & 15)])
      }
    }
    return out
  }
}
