plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.android.kmp.library)
}

kotlin {
  jvmToolchain(17)
  jvm()
  android {
    namespace = "ee.schimke.livery.client"
    compileSdk = 37
    minSdk = 26
  }
  // Compiled for the browser; its tests are the common ones, run on the JVM (no headless browser in CI).
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser { testTask { enabled = false } } }
  iosArm64()
  iosSimulatorArm64()

  sourceSets {
    // JDK crypto, file stores and the OkHttp engine, shared by the JVM and Android.
    val javaMain by creating {
      dependsOn(commonMain.get())
      dependencies { implementation(libs.ktor.client.okhttp) }
    }
    jvmMain.get().dependsOn(javaMain)
    androidMain.get().dependsOn(javaMain)
    // A custom source set turns off the default hierarchy, so the Apple one is declared too.
    val iosMain by creating {
      dependsOn(commonMain.get())
      dependencies { implementation(libs.ktor.client.darwin) }
    }
    iosArm64Main.get().dependsOn(iosMain)
    iosSimulatorArm64Main.get().dependsOn(iosMain)
    commonMain.dependencies {
      api(project(":livery-protocol"))
      api(libs.kotlinx.coroutines.core)
      api(libs.ktor.client.core)
    }
    wasmJsMain.dependencies { implementation(libs.ktor.client.js) }
    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.ktor.client.mock)
    }
  }
}

// `-Plivery.conformance.update=true` rewrites conformance/ from this client's answers.
tasks.withType<Test>().configureEach {
  systemProperty("livery.conformance.update", providers.gradleProperty("livery.conformance.update").getOrElse("false"))
}
