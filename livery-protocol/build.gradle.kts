plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.android.kmp.library)
}

kotlin {
  // Plain data on every platform the clients run on. The Swift package ports these types and is
  // pinned to them by the conformance fixtures.
  jvmToolchain(17)
  jvm()
  android {
    namespace = "ee.schimke.livery.protocol"
    compileSdk = 37
    minSdk = 26
  }
  // Compiled for the browser; its tests are the common ones, run on the JVM (no headless browser in CI).
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser { testTask { enabled = false } } }
  iosArm64()
  iosSimulatorArm64()

  sourceSets {
    commonMain.dependencies { api(libs.kotlinx.serialization.json) }
    commonTest.dependencies { implementation(kotlin("test")) }
  }
}
