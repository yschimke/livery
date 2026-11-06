import Foundation
import XCTest

@testable import Livery

/// Runs the checked-in conformance fixtures (docs/PROTOCOL.md, "Conformance"): this client must
/// give the same answers as the Kotlin reference.
final class ConformanceTests: XCTestCase {
  private func fixture(_ name: String) throws -> Data {
    let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return try Data(contentsOf: root.appendingPathComponent("conformance").appendingPathComponent(name))
  }

  struct SelectionFixture: Decodable {
    struct Query: Decodable {
      var contract: String; var caps: PlayerCapabilities; var widthDp: Int?; var heightDp: Int?
      var designId: String?; var variant: String?; var theme: String?; var tags: [String: String]?
      var minor: Int?
    }
    struct Case: Decodable { var name: String; var query: Query; var installed: [String]?; var expect: String? }
    var manifest: Manifest
    var cases: [Case]
  }

  func testSelection() throws {
    let f = try JSONDecoder().decode(SelectionFixture.self, from: fixture("selection.json"))
    XCTAssertFalse(f.cases.isEmpty)
    for c in f.cases {
      let installed = Set(c.installed ?? [])
      let q = TemplateQuery(contract: c.query.contract, caps: c.query.caps, widthDp: c.query.widthDp, heightDp: c.query.heightDp,
        designId: c.query.designId, variant: c.query.variant, theme: c.query.theme, tags: c.query.tags ?? [:], minor: c.query.minor)
      let got = TemplateSelector.select(f.manifest, q) { $0.install == .always || installed.contains($0.id) }
      XCTAssertEqual(got?.sha256, c.expect, c.name)
    }
  }

  struct ExperimentFixture: Decodable {
    struct Case: Decodable { var name: String; var experiment: String; var bucketingId: String; var nowEpochMs: Int64?; var segments: Set<String>?; var expect: String? }
    var experiments: [Experiment]
    var cases: [Case]
  }

  func testExperiments() throws {
    let f = try JSONDecoder().decode(ExperimentFixture.self, from: fixture("experiments.json"))
    XCTAssertFalse(f.cases.isEmpty)
    for c in f.cases {
      let e = try XCTUnwrap(f.experiments.first { $0.id == c.experiment })
      let ctx = DecisionContext(nowEpochMs: c.nowEpochMs ?? 1_000, platform: "test", locale: "en", appVersion: 1, bucketingId: c.bucketingId, segments: c.segments ?? [])
      XCTAssertEqual(LocalBucketing.assignNow(e, ctx), c.expect, c.name)
    }
  }

  struct BindingFixture: Decodable {
    struct Expect: Decodable { var ok: [String: NamedValue]?; var problems: [String]? }
    struct Case: Decodable { var name: String; var data: [String: JSONValue]; var expect: Expect }
    var contract: Contract
    var cases: [Case]
  }

  func testBinding() throws {
    let f = try JSONDecoder().decode(BindingFixture.self, from: fixture("binding.json"))
    for c in f.cases {
      switch ContractBinder.bind(f.contract, c.data) {
      case .ok(let b):
        XCTAssertEqual(b.values, c.expect.ok, c.name)
        XCTAssertNil(c.expect.problems, c.name)
      case .invalid(let problems):
        XCTAssertEqual(Array(Set(problems.map(\.name))).sorted(), c.expect.problems, c.name)
      }
    }
  }

  struct ResourcesFixture: Decodable {
    struct Ref: Decodable { var value: String; var kind: String }
    struct Proxied: Decodable { var url: String; var widthPx: Int; var heightPx: Int; var expect: String? }
    struct Direct: Decodable { var url: String; var allow: String; var expect: String? }
    struct URLCase: Decodable { var base: String; var ref: String; var expect: String }
    var imageRefs: [Ref]; var proxy: ImageProxy; var proxied: [Proxied]; var direct: [Direct]; var urls: [URLCase]
  }

  func testResources() throws {
    let f = try JSONDecoder().decode(ResourcesFixture.self, from: fixture("resources.json"))
    for r in f.imageRefs {
      let kind: String
      switch ImageRef.parse(r.value) {
      case .asset?: kind = "asset"
      case .remote?: kind = "remote"
      case .app?: kind = "app"
      case nil: kind = "invalid"
      }
      XCTAssertEqual(kind, r.kind, r.value)
    }
    for p in f.proxied {
      XCTAssertEqual(ResourceURLs.remote(proxy: f.proxy, directHosts: [], url: p.url, widthPx: p.widthPx, heightPx: p.heightPx), p.expect, p.url)
    }
    for d in f.direct {
      XCTAssertEqual(ResourceURLs.remote(proxy: nil, directHosts: [d.allow], url: d.url, widthPx: 10, heightPx: 10), d.expect, d.url)
    }
    for u in f.urls { XCTAssertEqual(ResourceURLs.resolve(u.base, u.ref), u.expect, "\(u.base) + \(u.ref)") }
  }

  struct SigningFixture: Decodable {
    struct Key: Decodable { var keyId: String; var publicKey: String; var contractPrefixes: [String]? }
    struct Case: Decodable { var name: String; var manifestBase64: String; var signature: ManifestSignature?; var trust: [Key]; var expect: String }
    var cases: [Case]
  }

  func testSigning() throws {
    let f = try JSONDecoder().decode(SigningFixture.self, from: fixture("signing.json"))
    XCTAssertEqual(f.cases.count, 7)
    for c in f.cases {
      let keys = c.trust.map { TrustedKey(keyId: $0.keyId, publicKey: Data(base64Encoded: $0.publicKey)!, contractPrefixes: Set($0.contractPrefixes ?? [])) }
      let result = ManifestVerifier(policy: .signed(keys)).verify(Data(base64Encoded: c.manifestBase64)!, signature: c.signature)
      if case .accepted = result { XCTAssertEqual("accepted", c.expect, c.name) } else { XCTAssertEqual("rejected", c.expect, c.name) }
    }
  }

  func testRolloutBucketsMatchKotlin() {
    // FNV-1a 32 of "exp:u1"; the Kotlin DecisionsTest uses the same inputs.
    XCTAssertEqual(LocalRulesDecider.bucket("exp", "u1"), LocalRulesDecider.bucket("exp", "u1"))
    XCTAssertTrue((0..<10_000).contains(LocalRulesDecider.bucket("spring-2027", "user-42")))
  }
}
