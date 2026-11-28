package ee.schimke.livery.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.CommandLineArgumentProvider

/**
 * Captures a catalog's templates from a SwiftPM package using `LiveryCapture` and `RcCreation`:
 * runs `swift run --package-path <packageDir> <executable> <source> <out>`, writing the captured
 * catalog source tree into [outputDir] for `buildLiveryCatalog` to build.
 *
 * `RcCreation` and `LiveryCapture` are pure Swift, so this task runs on macOS (and Linux where
 * `Livery` targets Linux) without requiring Xcode UI frameworks.
 */
@CacheableTask
abstract class CaptureSwiftLiveryCatalog : Exec() {
  /** Directory containing the SwiftPM `Package.swift` that defines the template generator target. */
  @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val packageDir: DirectoryProperty

  /** Executable target name passed to `swift run`. */
  @get:Input abstract val executableName: Property<String>

  /** Optional hand-written catalog tree (`catalog.json`, `themes/`, `placements/`, `fonts/`). */
  @get:InputFiles @get:Optional @get:PathSensitive(PathSensitivity.RELATIVE) abstract val handWritten: DirectoryProperty

  /** Output directory receiving the captured catalog source tree (`contracts/`, `designs/`, etc.). */
  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  /** Path to the `swift` executable (defaults to `swift` on `PATH`). */
  @get:Input abstract val swiftExecutable: Property<String>

  init {
    swiftExecutable.convention("swift")
    val pkg = packageDir
    val exe = executableName
    val source = handWritten
    val out = outputDir
    val swift = swiftExecutable
    argumentProviders.add(CommandLineArgumentProvider {
      listOf(
        "run",
        "--package-path",
        pkg.get().asFile.absolutePath,
        exe.get(),
        source.orNull?.asFile?.takeIf { it.isDirectory }?.absolutePath ?: "",
        out.get().asFile.absolutePath,
      )
    })
    doFirst {
      executable = swift.get()
    }
  }

  @TaskAction
  override fun exec() {
    outputDir.get().asFile.apply {
      deleteRecursively()
      mkdirs()
    }
    super.exec()
  }
}
