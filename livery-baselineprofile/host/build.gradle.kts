// The app `:livery-baselineprofile` drives to collect the published libraries' baseline profile: a
// scrolling list of LiverySlots that plans and plays every design of the sample catalog, bundled
// as the app's catalog. Build-only; it is never published.
plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  // Gives the host the `nonMinifiedRelease` variant the generator runs against.
  alias(libs.plugins.androidx.baselineprofile)
}

android {
  namespace = "ee.schimke.livery.baselineprofile.host"
  compileSdk { version = release(37) { minorApiLevel = 1 } }
  defaultConfig {
    applicationId = "ee.schimke.livery.baselineprofile.host"
    // Profile capture without root needs API 33; the floor only has to cover the emulator.
    minSdk = 28
    targetSdk = 37
    versionCode = 1
    versionName = "1.0"
  }
  buildTypes {
    getByName("release") {
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Never distributed; debug signing lets the benchmark variants install.
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  buildFeatures { compose = true }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  // The sample catalog, built by the livery CLI into the app's assets at livery/.
  sourceSets.getByName("main").assets.directories.add("build/generated/livery-assets")
  // The Compose UI twins of the sample designs, shared with livery-compose's TwinRenderParityTest.
  sourceSets.getByName("main").kotlin.directories.add("../twins/kotlin")
}

kotlin { jvmToolchain(17) }

// The CLI and the source catalog it builds: the same pair `livery build` uses on the command line.
val liveryCli by configurations.creating { isCanBeConsumed = false }
val sampleCatalogs by configurations.creating { isCanBeConsumed = false }

dependencies {
  implementation(project(":livery-compose"))
  implementation(libs.androidx.activity.compose)
  // DocumentActivity follows getting-started's startup: the splash screen held until the first screen is ready.
  implementation(libs.androidx.core.splashscreen)
  implementation(libs.cmp.foundation)
  liveryCli(project(":livery-publish"))
  sampleCatalogs(project(mapOf("path" to ":livery-capture-cmp", "configuration" to "sampleCatalogsElements")))
}

val buildSampleCatalog = tasks.register<JavaExec>("buildSampleCatalog") {
  description = "Builds the sample catalog into the host's assets with `livery build`."
  classpath = liveryCli
  mainClass.set("ee.schimke.livery.publisher.MainKt")
  val samples: FileCollection = sampleCatalogs
  val out = layout.buildDirectory.dir("generated/livery-assets/livery")
  inputs.files(samples).withPathSensitivity(PathSensitivity.RELATIVE)
  outputs.dir(out)
  argumentProviders += CommandLineArgumentProvider {
    listOf("build", samples.singleFile.resolve("catalog").absolutePath, out.get().asFile.absolutePath)
  }
  doFirst { out.get().asFile.deleteRecursively() }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(buildSampleCatalog) }
tasks.matching { it.name.contains("lint", ignoreCase = true) }.configureEach { dependsOn(buildSampleCatalog) }
