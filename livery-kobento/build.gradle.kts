plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

// Kobento (https://mcp.kobento.dev/, an independent Remote Compose implementation) as one authoring
// tool, as livery-uibuilder is UI Builder: its documents, plain AndroidX authoring JSON compiled here
// with remotecompose-json or a `.rc` it compiled, become catalog templates through TemplateImport,
// checked against the contract and against what the target's player plays (Kobento's 3D
// operations play on Kobento's players, never on AndroidX's). JVM only: the JSON compiler is.
// Published as a library, so a server can compile and check documents in-process; the CLI and
// livery-mcp find its command and tools by ServiceLoader. docs/KOBENTO.md has the plan.
kotlin { jvmToolchain(17) }

dependencies {
  // In its API: Target and Contract, CatalogProblems, McpExtension.
  api(project(":livery-protocol"))
  api(project(":livery-catalog"))
  api(project(":livery-mcp-core"))
  implementation(project(":livery-instantiate"))
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
  // AndroidX's writer, for a document authoring JSON can't express (a DrawContent in canvas operations).
  testImplementation(libs.compose.remote.creation.core)
}

// The tests read the built sample catalogs (their captured designs are build output).
apply(from = rootProject.file("gradle/sample-catalogs.gradle.kts"))
