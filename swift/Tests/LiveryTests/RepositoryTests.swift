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

  private func manifestJSON(serial: Int, experiments: String = "[]") -> (Data, [String: Data]) {
    let a = blob("a"), b = blob("b")
    let json = """
      {"catalog":"t","serial":\(serial),"generatedAtEpochMs":0,"expiresAtEpochMs":9000000000000,
       "contracts":[{"id":"c/tile","major":1,"inputs":{"name":{"type":"string","required":true}}}],
       "designs":[{"id":"builtin","contract":"c/tile@1","title":"Built-in","install":"Always"},
                  {"id":"skin","contract":"c/tile@1","title":"Skin","install":"OnRequest"}],
       "templates":[{"sha256":"\(Hashing.sha256Hex(a))","bytes":\(a.count),"designId":"builtin","contract":"c/tile@1","target":{"surface":"in-app","rcApiLevel":7}},
                    {"sha256":"\(Hashing.sha256Hex(b))","bytes":\(b.count),"designId":"skin","contract":"c/tile@1","target":{"surface":"in-app","rcApiLevel":7}}],
       "experiments":\(experiments)}
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

  func testEachDecisionInASlotIsItsOwnRenderedItem() async throws {
    let (m, blobs) = manifestJSON(serial: 1)
    var files = blobs
    files["manifest.json"] = m
    let repo = TemplateRepository(source: StaticSource("https://cdn.example/c"), fetcher: Host(files: files), trust: .allowUnsigned,
      blobs: MemoryBlobStore(), kv: MemoryKeyValueStore(), clock: { 1_000 }, targets: [caps])
    _ = await repo.refresh()
    let planner = SlotPlanner(repository: repo)
    let q = TemplateQuery(contract: "c/tile@1", caps: caps)
    func plan(_ id: String) async -> RenderPlan? {
      let d = Decision(placement: "home.top", decisionId: id, template: TemplateRef(designId: "builtin"), data: ["name": .string("Ad")])
      if case .ready(let p) = await planner.forDecision(q, d) { return p }
      return nil
    }
    let first = await plan("d1"), again = await plan("d1"), second = await plan("d2")
    XCTAssertNotNil(first)
    XCTAssertEqual(first?.renderIdentity, again?.renderIdentity, "the same item keeps its identity across renders")
    XCTAssertNotEqual(first?.renderIdentity, second?.renderIdentity, "a new decision in the slot is a new item")
  }

  struct Fixed: ExperimentAssigner {
    let arm: String
    func assign(_ experiment: Experiment, context: DecisionContext) async -> String? { arm }
  }

  final class Log: @unchecked Sendable {
    private let lock = NSLock()
    private var all: [TemplateEvent] = []
    func add(_ e: TemplateEvent) { lock.lock(); all.append(e); lock.unlock() }
    var exposures: [TemplateEvent] { lock.lock(); defer { lock.unlock() }; return all.filter { $0.type == .exposure } }
    var events: [TemplateEvent] { lock.lock(); defer { lock.unlock() }; return all }
  }

  func testDesignArmsIncludingTheControl() async throws {
    let (m, blobs) = manifestJSON(serial: 1, experiments: """
      [{"id":"tile_look","arms":[{"id":"control"},{"id":"skin","designs":{"c/tile@1":"skin"}}]}]
      """)
    var files = blobs
    files["manifest.json"] = m
    let repo = TemplateRepository(source: StaticSource("https://cdn.example/c"), fetcher: Host(files: files), trust: .allowUnsigned,
      blobs: MemoryBlobStore(), kv: MemoryKeyValueStore(), clock: { 1_000 }, targets: [caps])
    _ = await repo.refresh()
    let q = TemplateQuery(contract: "c/tile@1", caps: caps)
    let data: [String: JSONValue] = ["name": .string("Hall")]
    func plan(_ arm: String, _ log: Log = Log()) async -> (RenderPlan?, Experiments) {
      let e = Experiments(manifest: { await repo.manifest() },
        context: { DecisionContext(nowEpochMs: 1_000, locale: "en", appVersion: 1, bucketingId: "u") },
        assigner: Fixed(arm: arm), events: { log.add($0) })
      if case .ready(let p) = await SlotPlanner(repository: repo, events: e.sink, experiments: e).forAppData(q, data: data) { return (p, e) }
      return (nil, e)
    }

    // The skin is OnRequest and not installed: its arm draws the built-in, untagged.
    let (notInstalled, _) = await plan("skin")
    XCTAssertEqual(notInstalled?.template.entry.designId, "builtin")
    XCTAssertEqual(notInstalled?.experiments, [:])

    // The control picks no design: the built-in, tagged, and exposed once when it draws.
    let log = Log()
    let (control, e) = await plan("control", log)
    XCTAssertEqual(control?.template.entry.designId, "builtin")
    XCTAssertEqual(control?.experiments, ["tile_look": "control"])
    XCTAssertTrue(log.exposures.isEmpty, "planning isn't seeing")
    let impression = TemplateEvent(type: .impression, atEpochMs: 1_000, designId: "builtin", experiments: control?.experiments ?? [:])
    e.sink(impression)
    e.sink(impression)
    XCTAssertEqual(log.exposures.map(\.experiments), [["tile_look": "control"]])

    // Installed, the skin's arm draws it, tagged.
    let installed = await repo.install("skin")
    XCTAssertTrue(installed)
    let (skin, _) = await plan("skin")
    XCTAssertEqual(skin?.template.entry.designId, "skin")
    XCTAssertEqual(skin?.experiments, ["tile_look": "skin"])
  }

  func testTrackActionsNeedNoHandlerAndEventsCarryOnlyDeclaredArguments() async throws {
    let json = """
      {"id":"c/card","major":1,"actions":{"expand":{"kind":"track","args":{"section":{"type":"string"}}},"open":{"kind":"navigate"}}}
      """
    let contract = try JSONDecoder().decode(Contract.self, from: Data(json.utf8))
    let entry = try JSONDecoder().decode(TemplateEntry.self, from: Data("""
      {"sha256":"ab","bytes":1,"designId":"card","contract":"c/card@1","target":{"surface":"in-app","rcApiLevel":7}}
      """.utf8))
    let plan = RenderPlan(template: LoadedTemplate(entry: entry, design: nil, bytes: Data(), origin: .fresh, contract: contract, assets: []),
      contract: contract, bindings: Bindings(), decision: nil, feedItem: nil, experiments: ["look": "b"])
    let log = Log()
    let router = ActionRouter(handlers: [:], urlAllowed: { _ in false }, events: { log.add($0) }, clock: { 5 })
    await router.dispatch(plan, "expand", ["section": "specs", "note": "free text"])
    await router.dispatch(plan, "open", [:])
    XCTAssertEqual(log.events.map(\.type), [.action, .error])
    XCTAssertEqual(log.events.first?.args, ["section": "specs"])
    XCTAssertEqual(log.events.first?.experiments, ["look": "b"])
    XCTAssertEqual(log.events.last?.detail, "no handler for navigate")
    XCTAssertEqual(plan.slotTag, "livery:c/card@1:card")
  }

  func testActionArguments() {
    XCTAssertEqual(ActionRouter.arguments(fromText: nil), [:])
    XCTAssertEqual(ActionRouter.arguments(fromText: #"{"route":"/premium"}"#), ["route": "/premium"])
    XCTAssertEqual(ActionRouter.arguments(fromText: "/a"), ["value": "/a"])
    XCTAssertNil(ActionRouter.arguments(fromText: #"{"nested":{"a":1}}"#))
  }
}
