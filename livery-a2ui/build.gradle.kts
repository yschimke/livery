plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
}

// Livery contracts in A2UI's terms (docs/A2UI.md), with no A2UI library and no Compose: the
// component a contract becomes, its catalog JSON schema, the dataset its bound properties make, a
// `set`'s value as the data model holds it, and the event a template's action sends. Renderers
// (livery-a2ui-compose, and later the web and Swift ones) build on it; `livery build --a2ui` writes
// the same schema for agents. Pinned by conformance/a2ui.json.
kotlin {
  jvmToolchain(17)
  jvm()
  android {
    namespace = "ee.schimke.livery.a2ui"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    minSdk = 26
    aarMetadata { minCompileSdk = 36 }
  }
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser { testTask { enabled = false } } }
  iosArm64()
  iosSimulatorArm64()

  sourceSets {
    commonMain.dependencies {
      api(project(":livery-protocol"))
      api(project(":livery-client"))
    }
    commonTest.dependencies { implementation(kotlin("test")) }
  }
}

// `-Plivery.conformance.update=true` rewrites conformance/a2ui.json from this projection's answers.
tasks.withType<Test>().configureEach {
  systemProperty("livery.conformance.update", providers.gradleProperty("livery.conformance.update").getOrElse("false"))
}
