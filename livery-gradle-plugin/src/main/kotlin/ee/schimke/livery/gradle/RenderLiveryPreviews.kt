package ee.schimke.livery.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

/**
 * Previews every template in the catalog, with nothing written by hand: each design's templates
 * with each of its contract's examples, in each mode its target allows (light, dark, each theme
 * pack; a baked template in its own), at the size it was made for, over its theme's surface.
 * Writes `<design>/<template>-<example>-<mode>.png`, `previews.json` and an `index.html` into
 * [outputDir]. Fails when an example doesn't bind or a template can't be read, after drawing the rest.
 *
 * Runs `livery-render`'s `CatalogPreviews` (Compose's player on Skia) in its own JVM on
 * [getClasspath], as capture does: Compose and its native graphics don't belong in the daemon.
 * Cacheable: its inputs are the catalog tree and the renderer's classpath.
 */
@CacheableTask
abstract class RenderLiveryPreviews : JavaExec() {
  /** The catalog source tree: the captured one when there's a capture, else `livery.source`. */
  @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val catalog: DirectoryProperty
  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  init {
    mainClass.set("ee.schimke.livery.render.CatalogPreviewsKt")
    // Read when the task runs, so the configuration cache keeps no paths.
    val catalog = catalog
    val out = outputDir
    argumentProviders.add(CommandLineArgumentProvider { listOf(catalog.get().asFile.absolutePath, out.get().asFile.absolutePath) })
    jvmArgs("-Djava.awt.headless=true")
  }
}
