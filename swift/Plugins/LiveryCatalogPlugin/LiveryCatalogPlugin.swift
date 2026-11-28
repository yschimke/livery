import Foundation
import PackagePlugin

/// SwiftPM Command Plugin that runs a project's Swift `LiveryCapture` template-generator target
/// and optionally invokes the `livery` CLI to build a catalog:
///
///   swift package --allow-writing-to-package-directory livery-build \
///     --target CatalogTemplates \
///     [--source catalog-source] \
///     --out App/Resources/livery \
///     [--livery /path/to/livery]
@main
struct LiveryCatalogPlugin: CommandPlugin {
  func performCommand(context: PluginContext, arguments: [String]) async throws {
    var extractor = ArgumentExtractor(arguments)
    guard let targetName = extractor.extractOption(named: "target").last, !targetName.isEmpty else {
      Diagnostics.error("Missing required --target <ExecutableTarget> argument")
      return
    }
    let sourceDir = extractor.extractOption(named: "source").last ?? ""
    guard let outDir = extractor.extractOption(named: "out").last, !outDir.isEmpty else {
      Diagnostics.error("Missing required --out <OutputDirectory> argument")
      return
    }
    let liveryCLI = extractor.extractOption(named: "livery").last

    let buildResult = try packageManager.build(.product(targetName), parameters: .init())
    guard buildResult.succeeded else {
      Diagnostics.error("Building \(targetName) failed:\n\(buildResult.logText)")
      return
    }
    guard let exe = buildResult.builtArtifacts.first(where: { $0.kind == .executable }) else {
      Diagnostics.error("No executable artifact produced for target '\(targetName)'")
      return
    }

    if let liveryCLI {
      let staged = context.pluginWorkDirectoryURL.appendingPathComponent("captured-source")
      try runProcess(executableURL: exe.url, arguments: [sourceDir, staged.path])
      try runProcess(
        executableURL: URL(fileURLWithPath: liveryCLI),
        arguments: ["build", "--inspect", staged.path, outDir]
      )
    } else {
      try runProcess(executableURL: exe.url, arguments: [sourceDir, outDir])
    }
  }

  private func runProcess(executableURL: URL, arguments: [String]) throws {
    let process = Process()
    process.executableURL = executableURL
    process.arguments = arguments
    try process.run()
    process.waitUntilExit()
    if process.terminationStatus != 0 {
      throw PluginError.processFailed(
        executable: executableURL.lastPathComponent,
        status: process.terminationStatus
      )
    }
  }
}

private enum PluginError: Error, CustomStringConvertible {
  case processFailed(executable: String, status: Int32)

  var description: String {
    switch self {
    case .processFailed(let executable, let status):
      return "\(executable) exited with status \(status)"
    }
  }
}
