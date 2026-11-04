import Foundation
import XCTest

@testable import Livery

/// The repository over a fake host: refresh, rollback refusal, install vs preview, offline bundled.
final class RepositoryTests: XCTestCase {
  struct Host: Fetcher {
    let files: [String: Data]
    func get(_ url: String, etag: String?) async -> FetchResult {
      files.first { url.hasSuffix($0.key) }.map { .ok($0.value, etag: nil) } ?? .failed(status: 404, message: "no \(url)")
    }
  }

  private func blob(_ s: String) -> Data { Data("rc:\(s)".utf8) }

  private func manifestJSON(serial: Int) -> (Data, [String: Data]) {
    let a = blob("a"), b = blob("b")
    let json = """
      {"catalog":"t","serial":\(serial),"generatedAtEpochMs":0,"expiresAtEpochMs":9000000000000,
       "contracts":[{"id":"c/tile","major":1,"inputs":{"name":{"type":"string","required":true}}}],
       "designs":[{"id":"builtin","contract":"c/tile@1","title":"Built-in","install":"Always"},
                  {"id":"skin","contract":"c/tile@1","title":"Skin","install":"OnRequest"}],
       "templates":[{"sha256":"\(Hashing.sha256Hex(a))","bytes":\(a.count),"designId":"builtin","contract":"c/tile@1","target":{"surface":"in-app","rcApiLevel":7}},
                    {"sha256":"\(Hashing.sha256Hex(b))","bytes":\(b.count),"designId":"skin","contract":"c/tile@1","target":{"surface":"in-app","rcApiLevel":7}}]}
      """
    return (Data(json.utf8), ["blobs/\(Hashing.sha256Hex(a)).rc": a, "blobs/\(Hashing.sha256Hex(b)).rc": b])
  }

  private let caps = PlayerCapabilities(surface: Surfaces.inApp, maxRcApiLevel: 7, profiles: [])

  func testRefreshInstallPreviewAndRollback() async throws {
    let (m, blobs) = manifestJSON(serial: 5)
    var files = blobs
    files["manifest.json"] = m
    let store = MemoryBlobStore(), kv = MemoryKeyValueStore()
    let repo = TemplateRepository(source: StaticSource("https://cdn.example/c"), fetcher: Host(files: files), trust: .allowUnsigned,
      blobs: store, kv: kv, clock: { 1_000 }, targets: [caps])
    let r = await repo.refresh()
    XCTAssertEqual(r, .updated(serial: 5))
    let q = TemplateQuery(contract: "c/tile@1", caps: caps)
    let resolved = await repo.resolve(q)
    XCTAssertEqual(resolved?.entry.designId, "builtin")
    var chosen = q
    chosen.designId = "skin"
    let notInstalled = await repo.resolve(chosen)
    XCTAssertEqual(notInstalled?.entry.designId, "builtin", "a chosen skin not downloaded draws the built-in")
    let preview = await repo.preview("skin", q)
    XCTAssertEqual(preview?.entry.designId, "skin")
    let installedBefore = await repo.installedIds()
    XCTAssertFalse(installedBefore.contains("skin"))
    let installed = await repo.install("skin")
    XCTAssertTrue(installed)

    let (old, _) = manifestJSON(serial: 4)
    var oldFiles = blobs
    oldFiles["manifest.json"] = old
    let repo2 = TemplateRepository(source: StaticSource("https://cdn.example/c"), fetcher: Host(files: oldFiles), trust: .allowUnsigned,
      blobs: store, kv: kv, clock: { 1_000 }, targets: [caps])
    let rolled = await repo2.refresh(force: true)
    if case .failed(let reason) = rolled { XCTAssertTrue(reason.contains("rollback")) } else { XCTFail("rollback accepted: \(rolled)") }
  }

  func testPlannerBindsAndRefusesBadData() async throws {
    let (m, blobs) = manifestJSON(serial: 1)
    var files = blobs
    files["manifest.json"] = m
    let repo = TemplateRepository(source: StaticSource("https://cdn.example/c"), fetcher: Host(files: files), trust: .allowUnsigned,
      blobs: MemoryBlobStore(), kv: MemoryKeyValueStore(), clock: { 1_000 }, targets: [caps])
    _ = await repo.refresh()
    let planner = SlotPlanner(repository: repo)
    let q = TemplateQuery(contract: "c/tile@1", caps: caps)
    if case .ready(let plan) = await planner.forAppData(q, data: ["name": .string("Kitchen")]) {
      XCTAssertEqual(plan.bindings.values["name"], .string("Kitchen"))
    } else { XCTFail("not ready") }
    if case .ready = await planner.forAppData(q, data: ["typo": .int(1)]) { XCTFail("bad data accepted") }
  }

  func testActionArguments() {
    XCTAssertEqual(ActionRouter.arguments(fromText: nil), [:])
    XCTAssertEqual(ActionRouter.arguments(fromText: #"{"route":"/premium"}"#), ["route": "/premium"])
    XCTAssertEqual(ActionRouter.arguments(fromText: "/a"), ["value": "/a"])
    XCTAssertNil(ActionRouter.arguments(fromText: #"{"nested":{"a":1}}"#))
  }
}
