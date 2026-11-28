import Foundation
import Livery
import LiveryCapture
import RcCreation
import XCTest

final class LiveryCaptureTests: XCTestCase {
  private let sessionContract = Contract(
    id: "confetti/session-card",
    major: 1,
    title: "Session card",
    inputs: [
      "title": InputSpec(type: .string, required: true, default: .string("Keynote")),
      "speakers": InputSpec(type: .string, default: .string("")),
      "room": InputSpec(type: .string, default: .string("")),
    ],
    actions: [
      "open": ActionSpec(kind: ActionKinds.navigate),
    ]
  )

  func testCapturesValidTemplateAndWritesCatalogSourceTree() throws {
    let captured = try LiveryCapture.capture(
      contract: sessionContract,
      width: 340,
      height: 88
    ) { values in
      let title = try values.string("title")
      let speakers = try values.string("speakers")
      let room = try values.string("room")
      let open = try values.action("open")

      return RemoteVStack(alignment: .leading, spacing: 4) {
        RemoteText("\(title)").font(.headline)
        RemoteText("\(speakers)").font(.subheadline)
        RemoteText("\(room)").font(.caption).foregroundStyle(.secondary)
      }
      .padding(12)
      .frame(maxWidth: .infinity)
      .onTapGesture(.host(open))
    }

    XCTAssertFalse(captured.bytes.isEmpty)
    XCTAssertEqual(captured.reads, ["room", "speakers", "title"])
    XCTAssertEqual(captured.emits, ["open"])
    XCTAssertEqual(captured.source["tool"], "swift-capture")

    let sidecar = try XCTUnwrap(
      JSONSerialization.jsonObject(with: try captured.sidecarJSON()) as? [String: Any]
    )
    XCTAssertEqual(sidecar["reads"] as? [String], ["room", "speakers", "title"])
    XCTAssertEqual(sidecar["emits"] as? [String], ["open"])

    let root = FileManager.default.temporaryDirectory
      .appendingPathComponent("livery-capture-\(UUID().uuidString)", isDirectory: true)
    defer { try? FileManager.default.removeItem(at: root) }

    let source = CatalogSource(directory: root)
    try source.writeCatalog("confetti-swift")
    try source.writeContract(sessionContract)
    try source.writeDesign(
      Design(
        id: "session-default",
        contract: sessionContract.ref,
        title: "Session card",
        install: .always
      ),
      templates: ["in-app": captured]
    )

    XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent("catalog.json").path))
    XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent("contracts/session-card.json").path))
    XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent("designs/session-default/design.json").path))
    XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent("designs/session-default/in-app.rc").path))
    XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent("designs/session-default/in-app.json").path))
  }

  func testRefusesUndeclaredInputWrongTypeAndUndeclaredAction() {
    let values = LiveryValues(contract: sessionContract)

    XCTAssertThrowsError(try values.string("missing")) { error in
      XCTAssertEqual(
        error as? LiveryCaptureError,
        .undeclaredInput(name: "missing", contract: "confetti/session-card@1")
      )
    }

    XCTAssertThrowsError(try values.int("title")) { error in
      XCTAssertEqual(
        error as? LiveryCaptureError,
        .wrongInputType(name: "title", expected: .int, actual: .string)
      )
    }

    XCTAssertThrowsError(try values.action("bookmark")) { error in
      XCTAssertEqual(
        error as? LiveryCaptureError,
        .undeclaredAction(name: "bookmark", contract: "confetti/session-card@1")
      )
    }
  }
}
