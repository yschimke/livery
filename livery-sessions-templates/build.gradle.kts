plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  `java-library`
}

// Livery's own kind of agent session (docs/SESSIONS.md): an agent drafts a Remote Compose template
// for one contract and target, and the person plays each draft before deciding. Its gate is the one
// documents arriving outside a catalog pass (livery-instantiate's TemplateInspector), so it lives
// apart from livery-sessions-server, which an app with only its own kinds uses without rc-players.
kotlin { jvmToolchain(17) }

dependencies {
  api(project(":livery-sessions-server"))
  api(project(":livery-instantiate"))
  implementation(libs.kotlinx.serialization.json)
  testImplementation(kotlin("test"))
  testImplementation(libs.ktor.server.test.host)
  // To build the risky documents the gate must refuse.
  testImplementation(libs.rc.player.protocol)
}

// The tests use the sample catalog's contracts and designs (build output).
apply(from = rootProject.file("gradle/sample-catalogs.gradle.kts"))
