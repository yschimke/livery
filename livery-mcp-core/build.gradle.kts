plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
}

// Livery's agent tools as a Model Context Protocol server, transport-free and on every platform:
// the JSON-RPC handling, the tools and the catalog source tree they read, over an Okio
// FileSystem. What only some hosts can do (render a PNG, read git history, check against a
// revision) comes through McpHost; livery-mcp is the JVM edge (stdio, Skia, git).
kotlin {
  jvmToolchain(17)
  jvm()
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser { testTask { enabled = false } } }
  iosArm64()
  iosSimulatorArm64()

  sourceSets {
    commonMain.dependencies {
      api(project(":livery-catalog"))
      api(project(":livery-instantiate"))
      // a2ui_component_schema and validate_a2ui: contracts in A2UI's terms, and an agent's messages checked.
      api(project(":livery-a2ui"))
      api(libs.kotlinx.serialization.json)
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(libs.okio.fakefilesystem)
      implementation(libs.kotlinx.coroutines.test)
    }
  }
}

// The tests read the built sample catalogs (their captured designs are build output).
apply(from = rootProject.file("gradle/sample-catalogs.gradle.kts"))
