plugins {
  alias(libs.plugins.kotlin.jvm)
  `java-gradle-plugin`
}

// Builds a Livery catalog source tree into an app's assets at build time: the copy a fresh install
// plays before it has downloaded anything (TemplateRepository.Bundled).
kotlin { jvmToolchain(17) }

gradlePlugin {
  plugins {
    create("liveryCatalog") {
      id = "ee.schimke.composeai.livery.catalog"
      implementationClass = "ee.schimke.livery.gradle.LiveryCatalogPlugin"
      displayName = "Livery catalog"
      description = "Builds a Livery catalog into an Android app's assets"
    }
  }
}

dependencies {
  implementation(project(":livery-publish"))
  // Only the variant API; the app's own Android Gradle Plugin is the one on the classpath.
  compileOnly(libs.android.gradle.api)
  testImplementation(kotlin("test"))
  testImplementation(gradleTestKit())
}

// The tests read the built sample catalogs (their captured designs are build output).
apply(from = rootProject.file("gradle/sample-catalogs.gradle.kts"))

// The version of livery-render a release publishes beside this plugin: renderLiveryPreviews resolves
// it unless the build names its own renderer. Read when the task runs: release-tasks.gradle.kts sets
// each published module's version after this script is evaluated.
val pluginVersions = tasks.register("pluginVersions") {
  val renderVersion = provider { project(":livery-render").version.toString() }
  val out = layout.buildDirectory.dir("generated/pluginVersions")
  inputs.property("liveryRender", renderVersion)
  outputs.dir(out)
  doLast {
    val dir = out.get().asFile.resolve("ee/schimke/livery/gradle").apply { mkdirs() }
    dir.resolve("PluginVersions.kt").writeText(
      "package ee.schimke.livery.gradle\n\n" +
        "/** Generated: the versions of Livery's own artifacts this plugin was built beside. */\n" +
        "internal object PluginVersions {\n  const val liveryRender: String = \"${renderVersion.get()}\"\n}\n"
    )
  }
}
kotlin.sourceSets.main { kotlin.srcDir(pluginVersions) }

// The tests render with this build's livery-render, not a published one.
val previewRenderer by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
  attributes {
    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
  }
}
dependencies { previewRenderer(project(":livery-render")) }
tasks.withType<Test>().configureEach {
  val renderer: FileCollection = previewRenderer
  inputs.files(renderer).withNormalizer(ClasspathNormalizer::class).withPropertyName("previewRenderer")
  doFirst { systemProperty("livery.previewRenderer", renderer.asPath) }
}
