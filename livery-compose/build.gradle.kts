plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
  alias(libs.plugins.androidx.baselineprofile)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

// Compose Multiplatform client: Livery templates played by rc-players' Compose player on Android,
// desktop (JVM), iOS and the browser (Wasm). Apple targets compile only on a macOS host.
kotlin {
  jvmToolchain(17)
  android {
    namespace = "ee.schimke.livery.compose"
    // Compose Multiplatform's AndroidX artifacts need 37.1; OkHttp 5 needs 37.
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    minSdk = 26
    // Built against 37.1 for its dependencies; nothing here needs more than 36 of an app.
    aarMetadata { minCompileSdk = 36 }
  }
  jvm()
  // Compiled for the browser; its tests are the common ones, run on the JVM (no headless browser in CI).
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser { testTask { enabled = false } } }
  iosArm64()
  iosSimulatorArm64()

  sourceSets {
    // Skia-backed targets decode images and fonts the same way; Android has its own.
    val skikoMain by creating { dependsOn(commonMain.get()) }
    jvmMain.get().dependsOn(skikoMain)
    wasmJsMain.get().dependsOn(skikoMain)
    // A custom source set turns off the default hierarchy, so both Apple targets are wired here.
    iosArm64Main.get().dependsOn(skikoMain)
    iosSimulatorArm64Main.get().dependsOn(skikoMain)
    commonMain.dependencies {
      api(project(":livery-client"))
      api(project.dependencies.platform(libs.rc.players.bom))
      api(libs.rc.player.compose)
      // forDisplay: templates rewritten for the display they are drawn on (LiveryResources.ForDisplay).
      implementation(libs.rc.player.edit)
      api(libs.cmp.ui)
      implementation(libs.cmp.runtime)
      implementation(libs.cmp.foundation)
    }
    commonTest.dependencies { implementation(kotlin("test")) }
    // First-frame tests: Compose UI tests on the desktop JVM, playing the sample catalog's documents.
    jvmTest.dependencies {
      implementation(compose.desktop.currentOs)
      implementation(libs.cmp.ui.test)
    }
  }
}

// The Wasm browser test task is off (see wasmJs above), so there is no Compose UI test bundle for
// this configuration check to guard; `binaries.executable()` would turn a library into an app.
tasks.matching { it.name == "checkComposeUiTestConfigurationForWasmJs" }.configureEach { enabled = false }

// The tests read the built sample catalogs (their captured designs are build output).
apply(from = rootProject.file("gradle/sample-catalogs.gradle.kts"))

// Ships this module's share of the baseline profile collected by flinging the sample catalog
// (livery-baselineprofile/README.md); normal builds only package the checked-in file.
// Also the Remote Compose player's code on the paths Livery drives (prepared documents, state
// seeds, background preparation): rc-players ships a profile of its own corpus, and the rules add up.
baselineProfile {
  filter {
    include("ee.schimke.livery.compose.**")
    include("ee.schimke.composeai.rcplayer.**")
  }
  automaticGenerationDuringBuild = false
  saveInSrc = true
}

dependencies { baselineProfile(project(":livery-baselineprofile")) }
