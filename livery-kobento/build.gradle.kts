plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

// Kobento (https://mcp.kobento.dev/, an independent Remote Compose implementation) as one authoring
// tool, as livery-uibuilder is UI Builder: its documents, plain AndroidX authoring JSON compiled here
// with remotecompose-json or a `.rc` it compiled, become catalog templates through TemplateImport,
// checked against the contract and against what the target's player plays (Kobento's 3D
// operations play on Kobento's players, never on AndroidX's). JVM only: the JSON compiler is.
// docs/KOBENTO.md has the plan.
kotlin { jvmToolchain(17) }

dependencies {
  implementation(project(":livery-protocol"))
  implementation(project(":livery-catalog"))
  implementation(project(":livery-instantiate"))
  implementation(project(":livery-mcp-core"))
  implementation(platform(libs.rc.players.bom))
  implementation(libs.rc.player.edit)
  implementation(libs.rc.player.protocol)
  implementation(libs.remotecompose.json)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.okio)
  // LiveryCommand: the CLI that loads this module brings livery-publish itself.
  compileOnly(project(":livery-publish"))
  testImplementation(project(":livery-publish"))
  testImplementation(project(":livery-mcp"))
  testImplementation(kotlin("test"))
}

// The tests read the built sample catalogs (their captured designs are build output).
apply(from = rootProject.file("gradle/sample-catalogs.gradle.kts"))
