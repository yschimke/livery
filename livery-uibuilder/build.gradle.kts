plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

// Optional: Compose UI Builder as one authoring tool among others. It exports a UI Builder design
// (at a revision) as a Remote Compose document with its provenance; Livery's own tools then gate,
// render and publish it like any other document.
kotlin { jvmToolchain(17) }

dependencies {
  implementation(project(":livery-mcp"))
  implementation(libs.kotlinx.serialization.json)
  testImplementation(kotlin("test"))
}
