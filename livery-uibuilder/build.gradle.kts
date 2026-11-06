plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

// Optional: Compose UI Builder as one authoring tool among others. It exports a UI Builder design
// (at a revision) as a Remote Compose document with its provenance; Livery's own tools then gate,
// render and publish it like any other document. It plugs into both CLIs at run time (`livery
// ui-builder …` as a LiveryCommand, `livery-mcp --ui-builder` as an McpExtension), which put their
// own code on its class path, so neither depends on it.
kotlin { jvmToolchain(17) }

dependencies {
  compileOnly(project(":livery-mcp"))
  compileOnly(project(":livery-publish"))
  implementation(libs.kotlinx.serialization.json)
  testImplementation(project(":livery-mcp"))
  testImplementation(project(":livery-publish"))
  testImplementation(kotlin("test"))
}
