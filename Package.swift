// swift-tools-version:6.0
// Livery for Apple platforms: a native Swift client (URLSession, CryptoKit, Codable) for the same
// protocol as the Kotlin client, pinned to it by the shared conformance fixtures in conformance/.
import PackageDescription

let package = Package(
  name: "Livery",
  platforms: [.iOS(.v15), .macOS(.v12), .watchOS(.v8), .tvOS(.v15)],
  products: [
    .library(name: "Livery", targets: ["Livery"]),
    .library(name: "LiveryUI", targets: ["LiveryUI"]),
    .library(name: "LiveryRcNativePlayer", targets: ["LiveryRcNativePlayer"]),
    .library(name: "LiveryCapture", targets: ["LiveryCapture"]),
  ],
  dependencies: [
    // Pure-Swift mirror of yschimke/rc-players (no Kotlin/Native XCFramework binary target).
    .package(url: "https://github.com/yschimke/rc-swift.git", from: "4.1.0"),
  ],
  targets: [
    .target(name: "Livery", path: "swift/Sources/Livery"),
    .target(name: "LiveryUI", dependencies: ["Livery"], path: "swift/Sources/LiveryUI"),
    .target(
      name: "LiveryRcNativePlayer",
      dependencies: [
        "Livery",
        "LiveryUI",
        .product(name: "RcNativePlayerCore", package: "rc-swift"),
        .product(name: "RcNativePlayerUIKit", package: "rc-swift"),
        .product(name: "RcPlayerAppleFonts", package: "rc-swift"),
      ],
      path: "swift/Sources/LiveryRcNativePlayer"
    ),
    .target(
      name: "LiveryCapture",
      dependencies: [
        "Livery",
        .product(name: "RcCreation", package: "rc-swift"),
      ],
      path: "swift/Sources/LiveryCapture"
    ),
    // Reads ../../../conformance through #filePath, so tests always run the checked-in fixtures.
    .testTarget(name: "LiveryTests", dependencies: ["Livery"], path: "swift/Tests/LiveryTests"),
    .testTarget(
      name: "LiveryCaptureTests",
      dependencies: [
        "Livery",
        "LiveryCapture",
        .product(name: "RcCreation", package: "rc-swift"),
      ],
      path: "swift/Tests/LiveryCaptureTests"
    ),
  ],
  swiftLanguageModes: [.v5]
)
