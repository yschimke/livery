plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
}

// Building and checking a catalog source tree, on every platform: the validation, manifest
// assembly, seasons, kept examples, previews, imports and compatibility check `livery` runs, over a
// SourceFile rather than java.io.File. livery-publish is the JVM edge (files, git, signing keys, the
// CLI); a browser, an IDE plugin or an agent can build and check a tree in memory. The package is
// livery-publish's, so its users' imports don't change.
kotlin {
  jvmToolchain(17)
  jvm()
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser { testTask { enabled = false } } }
  iosArm64()
  iosSimulatorArm64()

  sourceSets {
    commonMain.dependencies {
      api(project(":livery-protocol"))
      api(project(":livery-client"))
      // TemplateInspector, for `--inspect`: what a document really reads and emits.
      api(project(":livery-instantiate"))
    }
    commonTest.dependencies { implementation(kotlin("test")) }
  }
}
