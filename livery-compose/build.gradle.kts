plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

// Compose Multiplatform client: Livery templates played by rc-players' Compose player on Android,
// desktop (JVM), iOS and the browser (Wasm). Apple targets compile only on a macOS host.
kotlin {
  jvmToolchain(17)
  android {
    namespace = "ee.schimke.livery.compose"
    compileSdk = 37
    minSdk = 26
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
      api(libs.cmp.ui)
      implementation(libs.cmp.runtime)
      implementation(libs.cmp.foundation)
    }
    commonTest.dependencies { implementation(kotlin("test")) }
  }
}
