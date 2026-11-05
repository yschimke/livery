plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  application
}

// livery-mcp: agent tools over a catalog source tree (Model Context Protocol over stdio). The
// contract, the gate and a render, for any agent; nothing here signs or ships to users.
kotlin { jvmToolchain(17) }

dependencies {
  implementation(project(":livery-publish"))
  implementation(project(":livery-render"))
  implementation(project(":livery-instantiate"))
  implementation(libs.kotlinx.serialization.json)
  testImplementation(kotlin("test"))
}

application {
  mainClass.set("ee.schimke.livery.mcp.MainKt")
  applicationName = "livery-mcp"
}

// Compose Desktop and AndroidX publish a few jars under the same file name (two `annotations`
// jars, for one): keep the first rather than fail the install.
tasks.withType<Sync>().configureEach { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
tasks.withType<AbstractArchiveTask>().configureEach { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }

// Optional authoring-tool extensions, found at run time (McpExtension); the server's own code never
// depends on them.
dependencies { runtimeOnly(project(":livery-uibuilder")) }
