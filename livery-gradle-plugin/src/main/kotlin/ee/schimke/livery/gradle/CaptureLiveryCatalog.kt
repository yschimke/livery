package ee.schimke.livery.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.CommandLineArgumentProvider

/**
 * Captures a catalog's templates from Kotlin: runs the app's capture entry point, a `main` whose
 * arguments are `<source> <out>`, in its own JVM on [getClasspath]. `<source>` is the hand-written
 * catalog tree ([handWritten]: `catalog.json`, themes, placements, fonts), or an empty string when
 * there is none; `<out>` is [outputDir], emptied first, where the entry point writes the catalog
 * tree with its captured `designs/` and `contracts/`. `buildLiveryCatalog` then builds that tree.
 *
 * A JVM of its own, not a worker in Gradle's: capture loads Compose and its native graphics, which
 * don't belong in the daemon. The task is cacheable: its inputs are the classpath (the designs'
 * compiled code), the main class, its JVM arguments and the hand-written tree.
 */
@CacheableTask
abstract class CaptureLiveryCatalog : JavaExec() {
  @get:InputDirectory @get:Optional @get:PathSensitive(PathSensitivity.RELATIVE) abstract val handWritten: DirectoryProperty
  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  init {
    // Properties, not values: read when the task runs, so the configuration cache keeps no paths.
    val source = handWritten
    val out = outputDir
    argumentProviders.add(CommandLineArgumentProvider { listOf(source.orNull?.asFile?.absolutePath ?: "", out.get().asFile.absolutePath) })
  }

  @TaskAction override fun exec() {
    // What the entry point no longer captures must not survive into the catalog.
    outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
    super.exec()
  }
}
