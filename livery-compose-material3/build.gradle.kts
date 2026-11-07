plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

// Material 3 for Livery hosts: a ColorScheme as Livery's theme roles. Optional, so livery-compose
// stays free of Material; an app that themes with Material 3 already has it.
kotlin {
  jvmToolchain(17)
  android {
    namespace = "ee.schimke.livery.compose.material3"
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
    commonMain.dependencies {
      api(project(":livery-client"))
      api(libs.cmp.material3)
      // Material 3's stable line predates Compose 1.12: hold the graph to the Compose Livery builds on.
      api(libs.cmp.ui)
    }
    commonTest.dependencies { implementation(kotlin("test")) }
  }
}

// The Wasm browser test task is off (see wasmJs above); livery-compose does the same.
tasks.matching { it.name == "checkComposeUiTestConfigurationForWasmJs" }.configureEach { enabled = false }
