package ee.schimke.livery.gradle

import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.SourceSetContainer

/**
 * ```
 * plugins { id("ee.schimke.composeai.livery.catalog") }
 * livery { source.set(layout.projectDirectory.dir("livery")) }
 * ```
 *
 * Builds the catalog in [LiveryCatalogExtension.source] into every Android variant's assets under
 * [LiveryCatalogExtension.assetsPath], checked like `livery build --inspect`, so the app ships the
 * catalog it was built with. At run time `AndroidBundled.fromAssets(context, assetsPath)` hands it
 * to the repository as its bundled copy: what a fresh install plays before it has downloaded
 * anything, and what it falls back to offline. Without the Android Gradle Plugin, only
 * `buildLiveryCatalog` is registered.
 *
 * With [LiveryCatalogExtension.capture] set, the templates are captured from Kotlin at build time
 * and never committed: `captureLiveryCatalog` runs the capture entry point over [source] (then
 * just the hand-written data) into `build/livery/captured`, and `buildLiveryCatalog` builds that.
 *
 * ```
 * livery {
 *   source.set(layout.projectDirectory.dir("catalog"))   // catalog.json, themes, placements, fonts
 *   capture { mainClass.set("com.example.catalog.CaptureKt") }
 * }
 * ```
 *
 * `renderLiveryPreviews` previews every template in the catalog (the captured tree, when there's a
 * capture) into `build/livery/previews`: each with each of its contract's examples, in each mode
 * its target allows, at its own size (see [RenderLiveryPreviews]). It renders with `livery-render`,
 * resolved at this plugin's version unless [LiveryPreviewsExtension.classpath] is set.
 */
class LiveryCatalogPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val ext = project.extensions.create("livery", LiveryCatalogExtension::class.java)
    ext.source.convention(project.layout.projectDirectory.dir("livery"))
    ext.assetsPath.convention("livery")
    ext.inspect.convention(true)
    ext.capture.outputDir.convention(project.layout.buildDirectory.dir("livery/captured"))
    ext.capture.jvmArgs.convention(emptyList())
    // The project's own code, when it has any: where a catalog's designs usually live.
    project.plugins.withId("java") {
      val main = project.extensions.getByType(SourceSetContainer::class.java).getByName("main")
      ext.capture.classpath.from(main.runtimeClasspath)
    }
    val capture = project.tasks.register("captureLiveryCatalog", CaptureLiveryCatalog::class.java) { t ->
      t.group = "build"
      t.description = "Captures the Livery catalog's templates from Kotlin into build/livery/captured"
      // Properties, never the extension: the configuration cache keeps what the task holds.
      val mainClass = ext.capture.mainClass
      t.classpath = ext.capture.classpath
      t.mainClass.set(mainClass)
      t.captureJvmArgs.set(ext.capture.jvmArgs)
      t.handWritten.set(ext.source.map { if (it.asFile.isDirectory) it else null })
      t.outputDir.set(ext.capture.outputDir)
      t.onlyIf("livery.capture.mainClass is set") { mainClass.isPresent }
    }
    val build = project.tasks.register("buildLiveryCatalog", BuildLiveryCatalog::class.java) { t ->
      t.group = "build"
      t.description = "Builds the Livery catalog into generated assets"
      // Captured when there is a capture entry point; the source tree as it is otherwise.
      t.source.set(ext.capture.mainClass.flatMap { capture.flatMap { c -> c.outputDir } }.orElse(ext.source))
      t.assetsPath.set(ext.assetsPath)
      t.inspect.set(ext.inspect)
      t.outputDir.set(project.layout.buildDirectory.dir("generated/livery/assets"))
    }
    // livery-render at the version this plugin was built with, unless the build names its own.
    val renderer = project.configurations.create("liveryPreviewRenderer") { c ->
      c.isCanBeConsumed = false
      c.isCanBeResolved = true
      c.description = "livery-render, for renderLiveryPreviews"
      c.attributes { a ->
        a.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        a.attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category::class.java, Category.LIBRARY))
        a.attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, project.objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.STANDARD_JVM))
      }
      c.defaultDependencies { it.add(project.dependencies.create("ee.schimke.composeai:livery-render:${PluginVersions.liveryRender}")) }
    }
    ext.previews.outputDir.convention(project.layout.buildDirectory.dir("livery/previews"))
    ext.previews.draw.convention(true)
    project.tasks.register("renderLiveryPreviews", RenderLiveryPreviews::class.java) { t ->
      t.group = "verification"
      t.description = "Previews every template in the Livery catalog, with each example in each mode, into build/livery/previews"
      val classpath = ext.previews.classpath
      t.classpath = project.files(project.provider { if (classpath.isEmpty) renderer else classpath })
      t.catalog.set(ext.capture.mainClass.flatMap { capture.flatMap { c -> c.outputDir } }.orElse(ext.source))
      t.outputDir.set(ext.previews.outputDir)
      t.draw.set(ext.previews.draw)
    }
    project.plugins.withId("com.android.base") {
      project.extensions.getByType(AndroidComponentsExtension::class.java).onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(build, BuildLiveryCatalog::outputDir)
      }
    }
  }
}

abstract class LiveryCatalogExtension {
  /** The catalog source tree (`catalog.json`, `contracts/`, `designs/`), as `livery build` reads it. */
  abstract val source: DirectoryProperty

  /** Where in the assets the catalog goes; the app passes the same path at run time. */
  abstract val assetsPath: Property<String>

  /** Decode every `.rc` and check it against its sidecar (`livery build --inspect`). */
  abstract val inspect: Property<Boolean>

  /** Capturing the templates from Kotlin at build time; unset, [source] is built as it is. */
  @get:Nested abstract val capture: LiveryCaptureExtension

  fun capture(action: Action<LiveryCaptureExtension>) = action.execute(capture)

  /** `renderLiveryPreviews`: every template, with each example in each mode. */
  @get:Nested abstract val previews: LiveryPreviewsExtension

  fun previews(action: Action<LiveryPreviewsExtension>) = action.execute(previews)
}

abstract class LiveryPreviewsExtension {
  /**
   * The renderer's classpath, when not `ee.schimke.composeai:livery-render` at this plugin's
   * version (resolved from the build's repositories): a newer renderer, or a local build of it.
   */
  abstract val classpath: ConfigurableFileCollection

  /** Where the previews go: `build/livery/previews`, by default. */
  abstract val outputDir: DirectoryProperty

  /**
   * Draw the previews with Compose Multiplatform's player (true, the default), or only export the
   * matrix as `cases.json` for a renderer on the platform's own players (false).
   */
  abstract val draw: Property<Boolean>
}

abstract class LiveryCaptureExtension {
  /**
   * The capture entry point: a class with `main(args)`, called with `<source> <out>`. It writes the
   * catalog tree into `<out>` (copying what it needs from `<source>`, the hand-written tree, or
   * `""` when there is none), with each design's `.rc` and sidecar under `designs/` and its
   * contracts under `contracts/`. Setting it turns capture on.
   */
  abstract val mainClass: Property<String>

  /** Where [mainClass] and the designs are: the project's main runtime classpath, by default. */
  abstract val classpath: ConfigurableFileCollection

  /** JVM arguments for the capture's JVM (e.g. `-Djava.awt.headless=true`). */
  abstract val jvmArgs: ListProperty<String>

  /** Where the captured catalog tree goes: `build/livery/captured`, by default. */
  abstract val outputDir: DirectoryProperty
}
