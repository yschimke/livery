package ee.schimke.livery.gradle

import ee.schimke.livery.publisher.build
import ee.schimke.livery.protocol.Manifest
import ee.schimke.livery.publisher.CatalogBuilder
import ee.schimke.livery.publisher.CatalogProblems
import ee.schimke.livery.publisher.Layout
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * The catalog as static files (`manifest.json`, `blobs/`, `assets/`, `previews/`) under
 * `<outputDir>/<assetsPath>/`. The output is the same for the same sources: the manifest's serial
 * and dates are fixed, so builds are reproducible and cacheable. A bundled catalog is never checked
 * for expiry, and any catalog the app downloads replaces it.
 */
@CacheableTask
abstract class BuildLiveryCatalog : DefaultTask() {
  @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val source: DirectoryProperty
  @get:Input abstract val assetsPath: Property<String>
  @get:Input abstract val inspect: Property<Boolean>
  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction fun build() {
    val path = assetsPath.get().trim('/')
    require(path.isNotEmpty() && path.split('/').none { it == ".." || it.isEmpty() }) { "livery.assetsPath must be a relative path: '$path'" }
    val builder = if (inspect.get()) CatalogBuilder(now = { BUNDLED_EPOCH_MS }, inspect = CatalogBuilder.INSPECT) else CatalogBuilder(now = { BUNDLED_EPOCH_MS })
    val out = try {
      builder.build(source.get().asFile, Layout.Static, serial = BUNDLED_SERIAL)
    } catch (e: CatalogProblems) {
      throw GradleException(e.message ?: "catalog has problems", e)
    }
    val root = outputDir.get().asFile.apply { deleteRecursively() }
    val dir = File(root, path).apply { mkdirs() }
    out.files.forEach { (p, bytes) -> File(dir, p).apply { parentFile.mkdirs() }.writeBytes(bytes) }
    File(dir, Manifest.FILE).writeBytes(out.manifestBytes)
    logger.lifecycle("Livery: ${out.manifest.catalog}/${out.manifest.channel}, ${out.manifest.templates.size} templates -> assets/$path")
  }

  companion object {
    /** Serial 0: anything the app downloads is newer. */
    const val BUNDLED_SERIAL: Long = 0
    /** A fixed date, so the same sources build the same bytes. */
    const val BUNDLED_EPOCH_MS: Long = 0
  }
}
