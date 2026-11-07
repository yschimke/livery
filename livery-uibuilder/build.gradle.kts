plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
}

// Compose UI Builder as one authoring tool. Common: the contract as host inputs
// (UiBuilderScaffold), the export API over Ktor (UiBuilderClient) and the MCP tools
// (UiBuilderExtension), so a browser or IDE serving livery-mcp-core can offer them. JVM: the
// `livery ui-builder` command and the service files livery and livery-mcp find them by.
kotlin {
  jvmToolchain(17)
  jvm()
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser { testTask { enabled = false } } }
  iosArm64()
  iosSimulatorArm64()

  sourceSets {
    commonMain.dependencies {
      implementation(project(":livery-mcp-core"))
      implementation(libs.ktor.client.core)
      implementation(libs.kotlinx.serialization.json)
    }
    jvmMain.dependencies {
      // LiveryCommand: the CLI that loads this module brings livery-publish itself.
      compileOnly(project(":livery-publish"))
      implementation(libs.ktor.client.okhttp)
    }
    wasmJsMain.dependencies { implementation(libs.ktor.client.js) }
    iosMain.dependencies { implementation(libs.ktor.client.darwin) }
    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(libs.ktor.client.mock)
      implementation(libs.kotlinx.coroutines.test)
    }
    jvmTest.dependencies {
      implementation(project(":livery-mcp"))
      implementation(project(":livery-publish"))
      implementation(kotlin("test"))
    }
  }
}
