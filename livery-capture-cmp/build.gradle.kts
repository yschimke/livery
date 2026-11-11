plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

// Authoring templates in Compose with rc-players' Compose Multiplatform creation API
// (ee.schimke.composeai.rccreation): the same LiveryValues and capture as livery-capture, with no
// Android, Robolectric or AndroidX alpha, so a template is captured by a plain JVM test, a server or
// a browser. livery-capture is the AndroidX binding, for content composed with AndroidX-only
// libraries; both sit on livery-capture-core.
kotlin {
  jvmToolchain(17)
  jvm()
  android {
    namespace = "ee.schimke.livery.capture.cmp"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    minSdk = 26
    // Built against 37.1 for its dependencies; nothing here needs more than 36 of an app.
    aarMetadata { minCompileSdk = 36 }
  }
  // Compiled for the browser; its tests are the JVM ones (no headless browser in CI).
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser { testTask { enabled = false } } }

  sourceSets {
    commonMain.dependencies {
      api(project(":livery-capture-core"))
      api(project.dependencies.platform(libs.rc.players.bom))
      api(libs.rc.creation.compose)
      // rcWidgetHostAction: a tap a launcher or Wear host can renumber.
      api(libs.rc.widget.creation)
      // What a widget player accepts: the profiles a widget target's capture is checked against.
      implementation(libs.rc.player.edit)
      implementation(libs.rc.player.protocol)
      implementation(libs.rc.widget.core)
      implementation(libs.cmp.runtime)
      api(libs.cmp.ui)
    }
    jvmTest.dependencies {
      implementation(kotlin("test"))
      implementation(libs.kotlinx.coroutines.core)
      implementation(project(":livery-instantiate"))
      // BindingAgreementTest draws both bindings' captures with the Compose player on Skia.
      implementation(project(":livery-render"))
      implementation(compose.desktop.currentOs)
    }
  }
}

// The Wasm browser test task is off (see wasmJs above), so there is no Compose UI test bundle for
// this configuration check to guard; `binaries.executable()` would turn a library into an app.
tasks.matching { it.name == "checkComposeUiTestConfigurationForWasmJs" }.configureEach { enabled = false }

// The sample catalogs' Compose-authored designs are Kotlin in this module's tests
// (samples/SampleDesigns.kt); their .rc and sidecars are build output, never committed.
// captureSamples runs SampleCatalogsTest with livery.capture.out set, on the plain JVM, and
// sampleCatalogs lays the captures over the hand-written source trees (samples/catalog,
// samples/two-way) as build/samples/{catalog,two-way}: what every module's tests, `livery build`
// in CI and the web tests read. Other modules consume it through gradle/sample-catalogs.gradle.kts.
val samplesSource = rootProject.layout.projectDirectory.dir("samples")
val capturedSamples = layout.buildDirectory.dir("captured-samples")
val jvmTest = tasks.named<Test>("jvmTest")

val captureSamples by tasks.registering(Test::class) {
  description = "Captures the sample catalogs' Compose-authored designs into build/captured-samples."
  testClassesDirs = files(jvmTest.map { it.testClassesDirs })
  classpath = files(jvmTest.map { it.classpath })
  filter { includeTestsMatching("ee.schimke.livery.capture.cmp.SampleCatalogsTest") }
  // The contracts the captures check against, read from the source tree.
  inputs.dir(samplesSource.dir("catalog/contracts")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("catalogContracts")
  inputs.dir(samplesSource.dir("two-way/contracts")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("twoWayContracts")
  outputs.dir(capturedSamples).withPropertyName("captures")
  val out = capturedSamples.get().asFile
  systemProperty("livery.capture.out", out.absolutePath)
  // DesignFolder.write removes what it no longer captures, but only in a design it still captures.
  doFirst { out.deleteRecursively() }
}

val sampleCatalogs by tasks.registering(Sync::class) {
  description = "Builds the sample catalog source trees, with their captured designs, into build/samples."
  into(layout.buildDirectory.dir("samples"))
  // Captures first: they win over a stale copy left in the source tree (which .gitignore keeps out of git).
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  from(files(capturedSamples).builtBy(captureSamples))
  from(samplesSource.dir("catalog")) { into("catalog") }
  from(samplesSource.dir("two-way")) { into("two-way") }
}

tasks.named("assemble") { dependsOn(sampleCatalogs) }

// The built sample catalogs, for other modules' tests: gradle/sample-catalogs.gradle.kts.
val sampleCatalogsElements by configurations.creating {
  isCanBeConsumed = true
  isCanBeResolved = false
}
artifacts.add(sampleCatalogsElements.name, sampleCatalogs.map { it.destinationDir }) { builtBy(sampleCatalogs) }

// The two bindings, compared: the same sample designs captured through AndroidX's creation API
// (:livery-capture:captureSamples, under Robolectric) and through rc-players' here. Its own task,
// so jvmTest and sampleCatalogs need no Android SDK; `check` runs it.
val androidxSampleCaptures by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
}
dependencies.add(androidxSampleCaptures.name, dependencies.project(mapOf("path" to ":livery-capture", "configuration" to "androidxSampleCapturesElements")))

jvmTest { filter { excludeTestsMatching("*BindingAgreementTest") } }

val bindingAgreementTest by tasks.registering(Test::class) {
  description = "Compares the AndroidX and Compose Multiplatform captures of the sample designs."
  group = "verification"
  testClassesDirs = files(jvmTest.map { it.testClassesDirs })
  classpath = files(jvmTest.map { it.classpath })
  filter { includeTestsMatching("*BindingAgreementTest") }
  inputs.dir(samplesSource.dir("catalog/contracts")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("catalogContracts")
  inputs.dir(samplesSource.dir("two-way/contracts")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("twoWayContracts")
  val androidx: FileCollection = androidxSampleCaptures
  inputs.files(androidx).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("androidxCaptures")
  val report = layout.buildDirectory.dir("reports/binding-agreement")
  outputs.dir(report).withPropertyName("report")
  // -Plivery.render.out=<dir> keeps the renders, to look at them.
  providers.gradleProperty("livery.render.out").orNull?.let { systemProperty("livery.render.out", it) }
  doFirst {
    systemProperty("livery.capture.androidx", androidx.singleFile.absolutePath)
    systemProperty("livery.agreement.report", report.get().asFile.absolutePath)
  }
}

tasks.named("check") { dependsOn(bindingAgreementTest) }
