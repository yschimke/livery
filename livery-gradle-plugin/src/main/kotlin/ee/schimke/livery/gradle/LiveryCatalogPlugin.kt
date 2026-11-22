package ee.schimke.livery.gradle

import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
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
import javax.inject.Inject

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
 * Templates designed in Compose UI Builder are captured the same way, with no UI Builder server:
 * `capture { uiBuilder() }` runs `livery-uibuilder-capture`'s entry point, which captures every
 * `designs/<id>/<name>.uid` in [source] with the UI Builder catalogs the project registers
 * (`ee.schimke.livery.uibuilder.capture.UiBuilderCatalog` in `META-INF/services`), each host
 * input bound to the design's contract. A project can call
 * `UiBuilderCapture.captureTree` from its own entry point to mix both.
 *
 * ```
 * livery {
 *   source.set(layout.projectDirectory.dir("catalog"))   // designs/<id>/design.json beside <name>.uid
 *   capture { uiBuilder() }
 * }
 * ```
 *
 * `renderLiveryPreviews` previews every template in the catalog (the captured tree, when there's a
 * capture) into `build/livery/previews`: each with each of its contract's examples, in each mode
 * its target allows, at its own size (see [RenderLiveryPreviews]). It renders with `livery-render`,
 * resolved at this plugin's version unless [LiveryPreviewsExtension.classpath] is set.
 *
 * One module can hold several catalogs, each built and bundled on its own (one per brand, say),
 * with [LiveryCatalogExtension.catalogs]: [source] then holds a tree per catalog, `<source>/<name>/`
 * (and a capture writes one per catalog, `<out>/<name>/`), and each has its own
 * `buildLiveryCatalog<Name>`, into the assets at `<assetsPath>/<name>`, and
 * `renderLiveryPreviews<Name>`, into `build/livery/previews/<name>`. `buildLiveryCatalog` and
 * `renderLiveryPreviews` then run them all.
 *
 * ```
 * livery {
 *   source.set(layout.projectDirectory.dir("catalogs"))   // catalogs/droidcon/, catalogs/kotlinconf/
 *   catalogs { register("droidcon"); register("kotlinconf") }
 * }
 * ```
 */
class LiveryCatalogPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val ext = project.extensions.create("livery", LiveryCatalogExtension::class.java)
    ext.source.convention(project.layout.projectDirectory.dir("livery"))
    ext.assetsPath.convention("livery")
    ext.inspect.convention(true)
    ext.defaultAnimations.convention(false)
    ext.capture.outputDir.convention(project.layout.buildDirectory.dir("livery/captured"))
    ext.capture.jvmArgs.convention(emptyList())
    ext.catalogs.configureEach { c ->
      c.source.convention(ext.source.dir(c.name))
      c.assetsPath.convention(ext.assetsPath.map { "${it.trim('/')}/${c.name}" })
    }
    // The project's own code, when it has any: where a catalog's designs usually live.
    project.plugins.withId("java") {
      val main = project.extensions.getByType(SourceSetContainer::class.java).getByName("main")
      ext.capture.classpath.from(main.runtimeClasspath)
    }
    // livery-uibuilder-capture at the version this plugin was built with, for `capture { uiBuilder() }`.
    val uiBuilderCapture = project.configurations.create("liveryUiBuilderCapture") { c ->
      c.isCanBeConsumed = false
      c.isCanBeResolved = true
      c.description = "livery-uibuilder-capture, for captureLiveryCatalog's UI Builder documents"
      c.attributes { a ->
        a.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        a.attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category::class.java, Category.LIBRARY))
        a.attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, project.objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.STANDARD_JVM))
      }
      c.defaultDependencies { it.add(project.dependencies.create("ee.schimke.composeai:livery-uibuilder-capture:${PluginVersions.liveryUiBuilderCapture}")) }
    }
    ext.capture.uiBuilder.convention(false)
    ext.capture.mainClass.convention(ext.capture.uiBuilder.flatMap { on ->
      project.provider { if (on) UI_BUILDER_MAIN else null }
    })
    val uiBuilderOn = ext.capture.uiBuilder
    ext.capture.classpath.from(project.provider { if (uiBuilderOn.get()) uiBuilderCapture else project.files() })
    val capture = project.tasks.register("captureLiveryCatalog", CaptureLiveryCatalog::class.java) { t ->
      t.group = "build"
      t.description = "Captures the Livery catalog's templates from Kotlin into build/livery/captured"
      // Properties, never the extension: the configuration cache keeps what the task holds.
      val mainClass = ext.capture.mainClass
      t.classpath = ext.capture.classpath
      t.mainClass.set(mainClass)
      t.captureJvmArgs.set(ext.capture.jvmArgs)
      // As it is, not filtered on whether it exists yet: a tree another task generates keeps that
      // task as a dependency, and is read when the capture runs.
      t.handWritten.set(ext.source)
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
      t.defaultAnimations.set(ext.defaultAnimations)
      t.outputDir.set(project.layout.buildDirectory.dir("generated/livery/assets"))
    }
    // With named catalogs, the single tree's task only runs them: there is no one tree to build.
    // A provider, not the extension, in the tasks: the configuration cache keeps what they hold.
    val single = project.provider { ext.catalogs.isEmpty() }
    build.configure { t ->
      t.dependsOn(project.provider { ext.catalogs.names.map { "buildLiveryCatalog${taskSuffix(it)}" } })
      t.onlyIf("livery.catalogs is empty") { single.get() }
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
      t.dependsOn(project.provider { ext.catalogs.names.map { "renderLiveryPreviews${taskSuffix(it)}" } })
      t.onlyIf("livery.catalogs is empty") { single.get() }
    }
    val catalogBuilds = mutableListOf<org.gradle.api.tasks.TaskProvider<BuildLiveryCatalog>>()
    ext.catalogs.all { c ->
      val name = c.name
      // The catalog's own tree: under the capture's output when there is a capture, else its source.
      val tree = ext.capture.mainClass.flatMap { capture.flatMap { t -> t.outputDir.dir(name) } }.orElse(c.source)
      catalogBuilds += project.tasks.register("buildLiveryCatalog${taskSuffix(name)}", BuildLiveryCatalog::class.java) { t ->
        t.group = "build"
        t.description = "Builds the $name Livery catalog into generated assets"
        t.source.set(tree)
        t.assetsPath.set(c.assetsPath)
        t.inspect.set(ext.inspect)
        t.defaultAnimations.set(ext.defaultAnimations)
        t.outputDir.set(project.layout.buildDirectory.dir("generated/livery/catalogs/$name"))
      }
      project.tasks.register("renderLiveryPreviews${taskSuffix(name)}", RenderLiveryPreviews::class.java) { t ->
        t.group = "verification"
        t.description = "Previews every template in the $name Livery catalog into build/livery/previews/$name"
        val classpath = ext.previews.classpath
        t.classpath = project.files(project.provider { if (classpath.isEmpty) renderer else classpath })
        t.catalog.set(tree)
        t.outputDir.set(ext.previews.outputDir.dir(name))
        t.draw.set(ext.previews.draw)
      }
    }
    project.plugins.withId("com.android.base") {
      project.extensions.getByType(AndroidComponentsExtension::class.java).onVariants { variant ->
        val assets = variant.sources.assets ?: return@onVariants
        if (catalogBuilds.isEmpty()) assets.addGeneratedSourceDirectory(build, BuildLiveryCatalog::outputDir)
        else catalogBuilds.forEach { assets.addGeneratedSourceDirectory(it, BuildLiveryCatalog::outputDir) }
      }
    }
  }

  private companion object {
    /** livery-uibuilder-capture's entry point: `<source> <out>`, every `.uid` in the tree captured. */
    const val UI_BUILDER_MAIN = "ee.schimke.livery.uibuilder.capture.UiBuilderCaptureKt"

    /** A catalog's name in a task's: `droidcon-london` is `DroidconLondon`. */
    fun taskSuffix(name: String): String =
      name.split('-', '_', '.').filter { it.isNotEmpty() }.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
  }
}

abstract class LiveryCatalogExtension {
  /** The catalog source tree (`catalog.json`, `contracts/`, `designs/`), as `livery build` reads it. */
  abstract val source: DirectoryProperty

  /** Where in the assets the catalog goes; the app passes the same path at run time. */
  abstract val assetsPath: Property<String>

  /** Decode every `.rc` and check it against its sidecar (`livery build --inspect`). */
  abstract val inspect: Property<Boolean>

  /**
   * Whether the templates keep AndroidX's default animations (`livery build --default-animations`). False, the
   * default: every component that asks for no animation lands at once ([ee.schimke.livery.publisher.TemplateProcessor]),
   * unless `catalog.json` or its sidecar says `defaultAnimations: true`. True publishes every `.rc` as it is.
   */
  abstract val defaultAnimations: Property<Boolean>

  /** Capturing the templates from Kotlin at build time; unset, [source] is built as it is. */
  @get:Nested abstract val capture: LiveryCaptureExtension

  fun capture(action: Action<LiveryCaptureExtension>) = action.execute(capture)

  /** `renderLiveryPreviews`: every template, with each example in each mode. */
  @get:Nested abstract val previews: LiveryPreviewsExtension

  fun previews(action: Action<LiveryPreviewsExtension>) = action.execute(previews)

  /**
   * Several catalogs in one module, each with its own tree under [source] (`<source>/<name>/`) and
   * its own build and preview tasks; empty, [source] is the one catalog.
   */
  abstract val catalogs: NamedDomainObjectContainer<LiveryCatalogSpec>

  fun catalogs(action: Action<NamedDomainObjectContainer<LiveryCatalogSpec>>) = action.execute(catalogs)
}

/** One of a module's [LiveryCatalogExtension.catalogs]. */
abstract class LiveryCatalogSpec @Inject constructor(private val name: String) : Named {
  override fun getName(): String = name

  /** The catalog's source tree: `<livery.source>/<name>`, by default. */
  abstract val source: DirectoryProperty

  /** Where in the assets the catalog goes: `<livery.assetsPath>/<name>`, by default. */
  abstract val assetsPath: Property<String>
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

  /**
   * Capture Compose UI Builder documents: every `designs/<id>/<name>.uid` in the source tree, drawn
   * with the UI Builder catalogs registered on [classpath], with no server. Set by [uiBuilder].
   */
  abstract val uiBuilder: Property<Boolean>

  /**
   * Captures the source tree's `.uid` templates with `livery-uibuilder-capture` (added to
   * [classpath], at this plugin's version unless the build declares `liveryUiBuilderCapture`
   * dependencies) as the entry point: [mainClass] need not be set.
   */
  fun uiBuilder() = uiBuilder.set(true)
}
