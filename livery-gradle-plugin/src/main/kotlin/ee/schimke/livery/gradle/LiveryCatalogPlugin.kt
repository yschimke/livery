package ee.schimke.livery.gradle

import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property

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
 */
class LiveryCatalogPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val ext = project.extensions.create("livery", LiveryCatalogExtension::class.java)
    ext.source.convention(project.layout.projectDirectory.dir("livery"))
    ext.assetsPath.convention("livery")
    ext.inspect.convention(true)
    val build = project.tasks.register("buildLiveryCatalog", BuildLiveryCatalog::class.java) { t ->
      t.group = "build"
      t.description = "Builds the Livery catalog into generated assets"
      t.source.set(ext.source)
      t.assetsPath.set(ext.assetsPath)
      t.inspect.set(ext.inspect)
      t.outputDir.set(project.layout.buildDirectory.dir("generated/livery/assets"))
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
}
