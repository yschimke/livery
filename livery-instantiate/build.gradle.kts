plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
}

// Writes a slot's values (and its images) into a values-free template, for hosts that only play
// the bytes they are handed: launcher widgets, Glance Wear widgets, server snapshots.
kotlin {
  jvmToolchain(17)
  jvm()
  android {
    namespace = "ee.schimke.livery.instantiate"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    minSdk = 26
    // Built against 37.1 for its dependencies; nothing here needs more than 36 of an app.
    aarMetadata { minCompileSdk = 36 }
  }
  // Compiled for the browser; its tests are the common ones, run on the JVM (no headless browser in CI).
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser { testTask { enabled = false } } }
  iosArm64()
  iosSimulatorArm64()

  sourceSets {
    commonMain.dependencies {
      api(project(":livery-client"))
      api(project.dependencies.platform(libs.rc.players.bom))
      implementation(libs.rc.player.edit)
      // rc-players' widget documents: baking, profile adaptation, Wear frames.
      api(libs.rc.widget.core)
      implementation(libs.rc.player.protocol)
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(libs.kotlinx.coroutines.test)
    }
  }
}
