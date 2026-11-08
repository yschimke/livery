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

  actor MutableHost: Fetcher {
    var files: [String: Data]
    init(_ files: [String: Data]) { self.files = files }
    func replace(_ files: [String: Data]) { self.files = files }
    func get(_ url: String, etag: String?) async -> FetchResult {
      files.first { url.hasSuffix($0.key) }.map { .ok($0.value, etag: nil) } ?? .failed(status: 404, message: "missing")
    }
  }

  func testFailedReplacementPreservesPlayableCacheAndRollbackWatermark() async throws {
    let (original, blobs) = manifestJSON(serial: 5)
    let store = MemoryBlobStore(), kv = MemoryKeyValueStore()
    var files = blobs; files["manifest.json"] = original
    let host = MutableHost(files)
    let repo = TemplateRepository(source: StaticSource("https://cdn.example/c"), fetcher: host, trust: .allowUnsigned,
      blobs: store, kv: kv, clock: { 1_000 }, targets: [caps])
    let first = await repo.refresh()
    XCTAssertEqual(first, .updated(serial: 5))
    let q = TemplateQuery(contract: "c/tile@1", caps: caps)
    let oldHash = Hashing.sha256Hex(blob("a")), newHash = Hashing.sha256Hex(blob("b"))
    let (next, _) = manifestJSON(serial: 6)
    let replacement = Data(String(decoding: next, as: UTF8.self).replacingOccurrences(of: oldHash, with: newHash).utf8)
    files["manifest.json"] = replacement; files.removeValue(forKey: "blobs/\(newHash).rc")
    await host.replace(files)
    let failed = await repo.refresh(force: true)
    if case .failed = failed {} else { XCTFail("replacement with missing blobs activated") }
    let stillPlayable = await repo.resolve(q)
    XCTAssertEqual(stillPlayable?.entry.sha256, oldHash)
    files["manifest.json"] = original; await host.replace(files)
    let rollback = await repo.refresh(force: true)
    if case .failed(let reason) = rollback { XCTAssertTrue(reason.contains("rollback")) } else { XCTFail("rollback accepted") }
    files["manifest.json"] = replacement; files["blobs/\(newHash).rc"] = blob("b")
    await host.replace(files)
    let repaired = await repo.refresh(force: true)
    XCTAssertEqual(repaired, .updated(serial: 6))
    let latest = await repo.resolve(q)
    XCTAssertEqual(latest?.entry.sha256, newHash)
  }

  final class MutableClock: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Int64 = 1_000
    func now() -> Int64 { lock.lock(); defer { lock.unlock() }; return value }
    func advance(_ milliseconds: Int64) { lock.lock(); defer { lock.unlock() }; value += milliseconds }
  }

  func testPendingReplacementRetriesBeforeTheOldTTLExpires() async throws {
    let (original, blobs) = manifestJSON(serial: 5)
    let clock = MutableClock()
    var files = blobs; files["manifest.json"] = original
    let host = MutableHost(files)
    let repo = TemplateRepository(source: StaticSource("https://cdn.example/c"), fetcher: host, trust: .allowUnsigned,
      blobs: MemoryBlobStore(), kv: MemoryKeyValueStore(), clock: { clock.now() }, targets: [caps])
    _ = await repo.refresh()
    let (next, _) = manifestJSON(serial: 6)
    let oldHash = Hashing.sha256Hex(blob("a")), newHash = Hashing.sha256Hex(blob("b"))
    files["manifest.json"] = Data(String(decoding: next, as: UTF8.self).replacingOccurrences(of: oldHash, with: newHash).utf8)
    files.removeValue(forKey: "blobs/\(newHash).rc")
    await host.replace(files)
    if case .failed = await repo.refresh(force: true) {} else { XCTFail("replacement activated") }
    clock.advance(60_001)
    files["blobs/\(newHash).rc"] = blob("b"); await host.replace(files)
    let retried = await repo.refresh()
    XCTAssertEqual(retried, .updated(serial: 6), "pending replacement must bypass the old TTL")
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

  func testScreensOpenFromADecisionOneLevelDeep() async throws {
    let card = blob("card"), full = blob("full")
    let json = """
      {"catalog":"t","serial":1,"generatedAtEpochMs":0,"expiresAtEpochMs":9000000000000,
       "contracts":[{"id":"c/card","major":1,"actions":{"details":{"kind":"present","placement":"promo.details"},"close":{"kind":"dismiss"}}},
                    {"id":"c/details","major":1,"inputs":{"title":{"type":"string"}},"actions":{"more":{"kind":"present","placement":"promo.details"},"close":{"kind":"dismiss"}}}],
       "designs":[{"id":"card","contract":"c/card@1","title":"Card","install":"Always"},
                  {"id":"details","contract":"c/details@1","title":"Details","install":"Always"}],
       "templates":[{"sha256":"\(Hashing.sha256Hex(card))","bytes":\(card.count),"designId":"card","contract":"c/card@1","target":{"surface":"in-app","rcApiLevel":7}},
                    {"sha256":"\(Hashing.sha256Hex(full))","bytes":\(full.count),"designId":"details","contract":"c/details@1","target":{"surface":"in-app","rcApiLevel":7,"container":"screen:fullscreen"}}]}
      """
    let files = ["manifest.json": Data(json.utf8), "blobs/\(Hashing.sha256Hex(card)).rc": card, "blobs/\(Hashing.sha256Hex(full)).rc": full]
    let placements = [ScreenPlacement(placement: "promo.details", contract: "c/details@1", allows: [.fullscreen])]
    // Screens open only from what is on the device, so the repository prefetches for their targets too.
    let repo = TemplateRepository(source: StaticSource("https://cdn.example/c"), fetcher: Host(files: files), trust: .allowUnsigned,
      blobs: MemoryBlobStore(), kv: MemoryKeyValueStore(), clock: { 1_000 }, targets: [caps] + LiveryScreens.targets(placements) { _ in self.caps })
    _ = await repo.refresh()
    let log = Log()
    let planner = SlotPlanner(repository: repo, events: { log.add($0) }, clock: { 1_000 })
    let screens = LiveryScreens(planner: planner, placements: placements,
      caps: { _ in self.caps }, context: { DecisionContext(nowEpochMs: 1_000, locale: "en", appVersion: 1, bucketingId: "u") },
      decide: { _, _ in nil }, appData: { _, _ in ["title": .string("More")] })
    let ad = Decision(placement: "home.top", decisionId: "d1", template: TemplateRef(designId: "card"),
      screens: ["details": Screen(template: TemplateRef(designId: "details"), data: ["title": .string("Sale")])])
    guard case .ready(let slot) = await planner.forDecision(TemplateQuery(contract: "c/card@1", caps: caps), ad) else { return XCTFail("no slot") }

    let opened = await screens.present(slot, "details", [:])
    XCTAssertTrue(opened)
    XCTAssertEqual(screens.stack.first?.plan.decision?.decisionId, "d1>details")
    XCTAssertEqual(screens.stack.first?.presentation, .fullscreen)
    let second = await screens.present(screens.stack[0].plan, "more", [:])
    XCTAssertTrue(second, "one level more")
    let third = await screens.present(screens.stack[1].plan, "more", [:])
    XCTAssertFalse(third, "no deeper")
    XCTAssertEqual(log.events.last?.detail, "a screen opened from a screen can't open another")

    // The two screens draw the same template from the same app data: alike in content, yet each
    // screen's actions are its own.
    XCTAssertEqual(screens.stack[0].plan.template.entry.sha256, screens.stack[1].plan.template.entry.sha256)
    XCTAssertEqual(screens.screen(of: screens.stack[1].plan)?.depth, 2)
    let router = ActionRouter(handlers: screens.handlers(), urlAllowed: { _ in false }, events: { log.add($0) })
    await router.dispatch(screens.stack[1].plan, "close", [:])
    XCTAssertEqual(screens.stack.count, 1, "the top screen's close closes only it")
    await router.dispatch(screens.stack[0].plan, "close", [:])
    XCTAssertTrue(screens.stack.isEmpty)
    XCTAssertEqual(log.events.filter { $0.type == .dismiss }.map(\.detail), ["action", "action"])
  }

  func testActionArguments() {
    XCTAssertEqual(ActionRouter.arguments(fromText: nil), [:])
    XCTAssertEqual(ActionRouter.arguments(fromText: #"{"route":"/premium"}"#), ["route": "/premium"])
    XCTAssertEqual(ActionRouter.arguments(fromText: "/a"), ["value": "/a"])
    XCTAssertNil(ActionRouter.arguments(fromText: #"{"nested":{"a":1}}"#))
  }
}
