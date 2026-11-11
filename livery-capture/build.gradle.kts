import com.android.build.api.variant.HostTestBuilder
import com.android.build.api.variant.LibraryAndroidComponentsExtension

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
}

// Authoring templates in Compose with AndroidX's own remote-creation-compose: named values typed by
// the contract, capture per target, and a design folder that a test keeps current. For content
// composed with AndroidX-only component libraries, and for the launcher and Glance Wear widget
// hosts, which take AndroidX's profiles. Used from an app's unit tests (Robolectric) or a
// build-time tool, never at runtime. livery-capture-cmp is the same on rc-players' Compose
// Multiplatform creation API, with no Android; both sit on livery-capture-core.
//
// One source, two bindings: LiveryValues is livery-capture-cmp's file, copied here at build time
// with its packages rewritten (generateAndroidxBinding), as rc-players builds
// rc-widget-creation-androidx. Nothing generated is committed. What is written by hand here is
// what AndroidX's capture alone has: a Context and a Profile (LiveryCapture.kt).

/**
 * Copies Kotlin sources, replacing each package prefix in [packages] (where an identifier starts
 * and the prefix is not part of a longer name) with its value.
 */
@CacheableTask
abstract class RewritePackages : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val sources: ConfigurableFileCollection

  /** The directory [sources] are relative to; a file keeps its path below it. */
  @get:Internal abstract val sourceRoot: DirectoryProperty

  @get:Input abstract val packages: MapProperty<String, String>

  /** The package directory of [sourceRoot] and the one its files are written under. */
  @get:Input abstract val fromDirectory: Property<String>

  @get:Input abstract val toDirectory: Property<String>

  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun rewrite() {
    val out = outputDirectory.get().asFile
    out.deleteRecursively()
    val root = sourceRoot.get().asFile
    val rules = packages.get().map { (from, to) -> Regex("(?<![A-Za-z0-9_.])" + Regex.escape(from) + "(?![A-Za-z0-9_])") to to }
    sources.files.sorted().forEach { file ->
      val relative = file.relativeTo(root).invariantSeparatorsPath
      check(relative.startsWith(fromDirectory.get() + "/")) { "$relative is outside the package" }
      val target = out.resolve(toDirectory.get() + relative.removePrefix(fromDirectory.get()))
      target.parentFile.mkdirs()
      val text = rules.fold(file.readText()) { text, (from, to) -> from.replace(text, to) }
      target.writeText("// Generated from :livery-capture-cmp's $relative by $name. Do not edit.\n$text")
    }
  }
}

/** rc-players' creation API and its widget helpers, as the packages AndroidX's binding has them in. */
fun RewritePackages.toAndroidx() {
  packages.put("ee.schimke.composeai.rccreation.compose", "androidx.compose.remote.creation.compose")
  packages.put("ee.schimke.composeai.rcwidget.creation", "ee.schimke.composeai.rcwidget.creation.androidx")
  packages.put("ee.schimke.livery.capture.cmp", "ee.schimke.livery.capture")
  fromDirectory.set("ee/schimke/livery/capture/cmp")
  toDirectory.set("ee/schimke/livery/capture")
}

// A `"../…"` literal on purpose: it is how .github/scripts/maven-publish-plan.sh learns that this
// module's artifact is built from that directory too, and publishes it when LiveryValues changes.
val cmpMain = layout.projectDirectory.dir("../livery-capture-cmp/src/commonMain/kotlin")
// Test sources reach no artifact, so these are named from the root, which the plan does not read.
val cmpTest = rootProject.layout.projectDirectory.dir("livery-capture-cmp/src/jvmTest/kotlin")

val generateAndroidxBinding by tasks.registering(RewritePackages::class) {
  description = "Writes livery-capture-cmp's LiveryValues against AndroidX's creation API."
  sourceRoot.set(cmpMain)
  sources.from(cmpMain.asFileTree.matching { include("**/LiveryValues.kt") })
  toAndroidx()
}

// The sample designs, for the tests that capture them through this binding (BindingAgreementTest
// compares the two). SampleCompat.kt is the seam: this module's tests have their own.
val generateAndroidxSamples by tasks.registering(RewritePackages::class) {
  description = "Writes livery-capture-cmp's sample designs against AndroidX's creation API."
  sourceRoot.set(cmpTest)
  sources.from(cmpTest.asFileTree.matching { include("**/samples/*.kt"); exclude("**/SampleCompat.kt") })
  toAndroidx()
}

extensions.configure<LibraryAndroidComponentsExtension> {
  onVariants { variant ->
    variant.sources.kotlin?.addGeneratedSourceDirectory(generateAndroidxBinding, RewritePackages::outputDirectory)
    variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.sources?.kotlin?.addGeneratedSourceDirectory(generateAndroidxSamples, RewritePackages::outputDirectory)
  }
}

android {
  namespace = "ee.schimke.livery.capture"
  compileSdk { version = release(37) { minorApiLevel = 1 } }
  defaultConfig {
    minSdk = 26
    aarMetadata { minCompileSdk = 36 }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

kotlin { jvmToolchain(17) }

dependencies {
  api(project(":livery-capture-core"))
  api(libs.compose.remote.creation.compose)
  api(platform(libs.rc.players.bom))
  // rcWidgetHostAction, AndroidX-typed: what rc-widget-launcher and rc-widget-wear take.
  api(libs.rc.widget.creation.androidx)
  implementation(libs.rc.widget.launcher)

  testImplementation(project(":livery-instantiate"))
  testImplementation(kotlin("test-junit"))
  testImplementation(libs.robolectric)
  testImplementation(libs.kotlinx.coroutines.core)
}

// Robolectric's SDK, resolved by Gradle so the tests run offline.
val robolectricSdk by configurations.creating {
  isCanBeConsumed = false
  isTransitive = false
}

dependencies { robolectricSdk(libs.robolectric.sdk35) }

val prepareRobolectricSdk by tasks.registering(Sync::class) {
  from(robolectricSdk)
  into(layout.buildDirectory.dir("robolectric-sdk"))
}

tasks.withType<Test>().configureEach {
  dependsOn(prepareRobolectricSdk)
  systemProperty("robolectric.offline", "true")
  systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-sdk").get().asFile.absolutePath)
}

// This binding's captures of the sample designs (SampleDesignsTest), for
// :livery-capture-cmp:bindingAgreementTest to compare with rc-players' captures of the same Kotlin.
// Build output, never committed; the sample catalogs themselves are :livery-capture-cmp:sampleCatalogs.
val samplesSource = rootProject.layout.projectDirectory.dir("samples")
val capturedSamples = layout.buildDirectory.dir("captured-samples")

val captureSamples by tasks.registering(Test::class) {
  description = "Captures the sample designs through AndroidX's creation API into build/captured-samples."
  val unitTest = tasks.named<Test>("testDebugUnitTest")
  testClassesDirs = files(unitTest.map { it.testClassesDirs })
  classpath = files(unitTest.map { it.classpath })
  filter { includeTestsMatching("ee.schimke.livery.capture.SampleDesignsTest") }
  // The contracts the captures check against, read from the source tree.
  inputs.dir(samplesSource.dir("catalog/contracts")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("catalogContracts")
  inputs.dir(samplesSource.dir("two-way/contracts")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("twoWayContracts")
  outputs.dir(capturedSamples).withPropertyName("captures")
  val out = capturedSamples.get().asFile
  systemProperty("livery.capture.out", out.absolutePath)
  // DesignFolder.write removes what it no longer captures, but only in a design it still captures.
  doFirst { out.deleteRecursively() }
}

val androidxSampleCapturesElements by configurations.creating {
  isCanBeConsumed = true
  isCanBeResolved = false
}
artifacts.add(androidxSampleCapturesElements.name, capturedSamples) { builtBy(captureSamples) }
