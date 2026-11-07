import CryptoKit
import Foundation

/// A publisher key the app trusts, optionally limited to contract prefixes (docs/PROTOCOL.md §3.2).
public struct TrustedKey: Sendable, Equatable {
  public var keyId: String
  /// The raw 32-byte Ed25519 public key.
  public var publicKey: Data
  public var contractPrefixes: Set<String>

  public init(keyId: String, publicKey: Data, contractPrefixes: Set<String> = []) {
    self.keyId = keyId; self.publicKey = publicKey; self.contractPrefixes = contractPrefixes
  }

  public func allows(_ contractRef: String) -> Bool {
    contractPrefixes.isEmpty || contractPrefixes.contains { contractRef.hasPrefix($0) }
  }
}

public enum TrustPolicy: Sendable {
  /// Development and preview channels only; never production slots.
  case allowUnsigned
  case signed([TrustedKey])
}

public enum Verification: Sendable {
  case accepted(Manifest, TrustedKey?)
  case rejected(String)
}

public struct ManifestVerifier: Sendable {
  public let policy: TrustPolicy
  public init(policy: TrustPolicy) { self.policy = policy }

  /// Ed25519 over the exact manifest bytes, then parse, then key scope: in that order.
  public func verify(_ manifestBytes: Data, signature: ManifestSignature?) -> Verification {
    var key: TrustedKey?
    if case .signed(let keys) = policy {
      guard let signature else { return .rejected("manifest is unsigned") }
      guard signature.alg == "Ed25519" else { return .rejected("unsupported alg \(signature.alg)") }
      guard let k = keys.first(where: { $0.keyId == signature.keyId }) else { return .rejected("untrusted key \(signature.keyId)") }
      guard let sig = Data(base64Encoded: signature.signature) else { return .rejected("signature is not base64") }
      guard let pub = try? Curve25519.Signing.PublicKey(rawRepresentation: k.publicKey),
        pub.isValidSignature(sig, for: manifestBytes)
      else { return .rejected("bad signature") }
      key = k
    }
    let manifest: Manifest
    do { manifest = try JSONDecoder().decode(Manifest.self, from: manifestBytes) } catch { return .rejected("unparseable: \(error)") }
    if let key {
      let outside = (manifest.templates.map(\.contract) + manifest.designs.map(\.contract)).filter { !key.allows($0) }
      if !outside.isEmpty { return .rejected("key \(key.keyId) may not sign \(Array(Set(outside)).sorted())") }
    }
    return .accepted(manifest, key)
  }
}

public enum Hashing {
  public static func sha256Hex(_ data: Data) -> String {
    SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
  }
}

/// §3.4: a signature over one document, Ed25519 over `livery-template:<contract id@major>:<sha256>`,
/// by the same trusted keys and held to the same contract prefixes as manifests. The Swift
/// counterpart of the Kotlin `TemplateSignatures`, pinned by `conformance/template-signing.json`.
public enum TemplateSignatures {
  public static func message(_ contractRef: String, _ sha256: String) -> Data { Data("livery-template:\(contractRef):\(sha256)".utf8) }

  /// The key that signed `sha256` for `contractRef`, or why the signature doesn't count.
  public static func verify(keys: [TrustedKey], contractRef: String, sha256: String, signature: ManifestSignature) -> TemplateVerification {
    guard signature.alg == "Ed25519" else { return .rejected("unsupported alg \(signature.alg)") }
    guard let key = keys.first(where: { $0.keyId == signature.keyId }) else { return .rejected("untrusted key \(signature.keyId)") }
    guard let sig = Data(base64Encoded: signature.signature) else { return .rejected("signature is not base64") }
    guard let pub = try? Curve25519.Signing.PublicKey(rawRepresentation: key.publicKey),
      pub.isValidSignature(sig, for: message(contractRef, sha256))
    else { return .rejected("bad signature") }
    guard key.allows(contractRef) else { return .rejected("key \(key.keyId) may not sign \(contractRef)") }
    return .accepted(key)
  }
}

public enum TemplateVerification: Sendable, Equatable {
  case accepted(TrustedKey)
  case rejected(String)
}
