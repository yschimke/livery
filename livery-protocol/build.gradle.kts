plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  // Common code only; every type here is plain data. Add targets as adopters need them
  // (androidTarget, iosArm64/iosSimulatorArm64, js, wasmJs): nothing in commonMain is JVM-specific.
  jvm()
  jvmToolchain(17)

  sourceSets {
    commonMain.dependencies { api(libs.kotlinx.serialization.json) }
    commonTest.dependencies { implementation(kotlin("test")) }
  }
}
