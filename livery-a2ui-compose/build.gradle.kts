plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

// Livery contracts as A2UI components (docs/A2UI.md). A prototype: not yet in the root
// build.gradle.kts's `published` list.
//
// AndroidX publishes A2UI for Android only; yschimke/a2ui-catalog ports the same release to Compose
// Multiplatform (JVM and Wasm) under upstream's own packages. So the code in src/a2ui is compiled
// twice: in androidMain against the AndroidX AARs (an Android app keeps the real library), and in
// portMain, shared by jvm and wasmJs, against the port. The AARs have no common variant, so the
// directory can't be commonMain (whose metadata compilation would need one).
//
// A2UI's alpha train pulls Compose 1.13 alphas (AndroidX Compose 1.13.0-alpha03, Compose
// Multiplatform 1.13.0-alpha02), ahead of the stable 1.12 line the core modules build on. That is
// this optional module's cost to its own users only.
kotlin {
  // The published port is Java 21 bytecode, so a desktop consumer needs Java 21 too.
  jvmToolchain(21)
  android {
    namespace = "ee.schimke.livery.a2ui.compose"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    minSdk = 26
    aarMetadata { minCompileSdk = 36 }
  }
  jvm()
  // The browser, against the port's Wasm build. Its tests are the JVM ones (no headless browser in
  // CI), as in livery-compose.
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser { testTask { enabled = false } } }

  sourceSets {
    // The port's two targets share one intermediate source set, as livery-compose's skikoMain does.
    val portMain by creating { dependsOn(commonMain.get()) }
    jvmMain.get().dependsOn(portMain)
    wasmJsMain.get().dependsOn(portMain)
    androidMain.get().kotlin.srcDir("src/a2ui/kotlin")
    portMain.kotlin.srcDir("src/a2ui/kotlin")
    commonMain.dependencies {
      api(project(":livery-compose"))
      implementation(libs.kotlinx.serialization.json)
    }
    androidMain.dependencies {
      api(libs.a2ui.compose.ui)
      implementation(libs.a2ui.material3)
      implementation(libs.a2ui.androidx.material3)
    }
    portMain.dependencies {
      api(libs.a2ui.cmp.compose.ui)
      implementation(libs.a2ui.cmp.material3)
      implementation(libs.a2ui.cmp.material3.base)
    }
    jvmTest.dependencies {
      implementation(kotlin("test"))
      implementation(project(":livery-render"))
      implementation(project(":livery-publish"))
      implementation(compose.desktop.currentOs)
      implementation(libs.a2ui.cmp.ui.test)
    }
  }
}

// -Plivery.render.out=<dir> keeps the test renders, to look at them.
tasks.withType<Test>().configureEach {
  providers.gradleProperty("livery.render.out").orNull?.let { systemProperty("livery.render.out", it) }
  systemProperty("livery.samples", rootProject.file("samples/catalog").absolutePath)
}
