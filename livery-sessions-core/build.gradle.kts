plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
}

// Agent sessions (docs/SESSIONS.md): a person and an AI agent editing one thing together through a
// rendezvous both connect out to. The model and rules, transport-free and on every platform: links,
// codes, sessions, the conversation (with the agent's steps), presence, long-polls and what is
// saved across a restart. Drafts and their checks stay with each kind's DraftStore; routes and MCP
// tools are livery-sessions-server's.
kotlin {
  jvmToolchain(17)
  jvm()
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser { testTask { enabled = false } } }
  iosArm64()
  iosSimulatorArm64()

  sourceSets {
    commonMain.dependencies {
      api(libs.kotlinx.coroutines.core)
      api(libs.kotlinx.serialization.json)
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(libs.kotlinx.coroutines.test)
    }
  }
}
