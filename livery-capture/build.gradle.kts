plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
}

// Authoring templates in Compose (AndroidX remote-creation-compose): named values typed by the
// contract, capture per target, and a design folder that a test keeps current. Used from an app's
// unit tests (Robolectric) or a build-time tool, never at runtime.
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
  api(project(":livery-client"))
  implementation(project(":livery-instantiate"))
  api(libs.compose.remote.creation.compose)
  api(platform(libs.rc.players.bom))
  api(libs.rc.widget.creation)
  implementation(libs.rc.widget.launcher)

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

// The sample catalogs' Compose-authored designs (SampleBannerTest, SamplePollTest,
// SampleQuantityTest) are Kotlin in this module's tests; their .rc and sidecars are build output,
// never committed. captureSamples runs those tests with livery.capture.out set, and sampleCatalogs
// lays the captures over the hand-written source trees (samples/catalog, samples/two-way) as
// build/samples/{catalog,two-way}: what every module's tests, `livery build` in CI and the web
// tests read. Other modules consume it through gradle/sample-catalogs.gradle.kts.
val samplesSource = rootProject.layout.projectDirectory.dir("samples")
val capturedSamples = layout.buildDirectory.dir("captured-samples")

val captureSamples by tasks.registering(Test::class) {
  description = "Captures the sample catalogs' Compose-authored designs into build/captured-samples."
  val unitTest = tasks.named<Test>("testDebugUnitTest")
  testClassesDirs = files(unitTest.map { it.testClassesDirs })
  classpath = files(unitTest.map { it.classpath })
  filter { includeTestsMatching("ee.schimke.livery.capture.Sample*Test") }
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
