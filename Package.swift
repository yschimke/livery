// swift-tools-version:5.9
// Livery for Apple platforms: a native Swift client (URLSession, CryptoKit, Codable) for the same
// protocol as the Kotlin client, pinned to it by the shared conformance fixtures in conformance/.
import PackageDescription

let package = Package(
  name: "Livery",
  platforms: [.iOS(.v15), .macOS(.v12), .watchOS(.v8), .tvOS(.v15)],
  products: [
    .library(name: "Livery", targets: ["Livery"]),
    .library(name: "LiveryUI", targets: ["LiveryUI"]),
  ],
  targets: [
    .target(name: "Livery", path: "swift/Sources/Livery"),
    .target(name: "LiveryUI", dependencies: ["Livery"], path: "swift/Sources/LiveryUI"),
    // Reads ../../../conformance through #filePath, so tests always run the checked-in fixtures.
    .testTarget(name: "LiveryTests", dependencies: ["Livery"], path: "swift/Tests/LiveryTests"),
  ]
)
