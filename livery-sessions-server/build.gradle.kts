plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  `java-library`
}

// Agent sessions over HTTP (docs/SESSIONS.md): the person's long-polled routes, the agent's MCP
// endpoint and a save file, around livery-sessions-core's relay. Each kind of session is a
// SessionKind the app registers: its drafts, their gate and any tools of its own.
kotlin { jvmToolchain(17) }

dependencies {
  api(project(":livery-sessions-core"))
  api(libs.ktor.server.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.core)
  testImplementation(kotlin("test"))
  testImplementation(libs.ktor.server.test.host)
  testImplementation(libs.kotlinx.coroutines.test)
}
