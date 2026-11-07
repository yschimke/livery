plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
}

// Building and checking a catalog source tree, on every platform: the validation, manifest
// assembly, seasons, kept examples, previews, imports and compatibility check `livery` runs, over
// an Okio FileSystem rather than java.io.File. livery-publish is the JVM edge (java.io.File, git,
// signing keys, the CLI); a browser, an IDE plugin or an agent can build and check a tree in a
// FakeFileSystem. The package is livery-publish's, so its users' imports don't change.
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
      // Source trees: the system's file system at the JVM edge, a FakeFileSystem in memory.
      api(libs.okio)
      implementation(libs.okio.fakefilesystem)
    }
    commonTest.dependencies { implementation(kotlin("test")) }
  }
}
