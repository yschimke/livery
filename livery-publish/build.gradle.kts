plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  application
}

kotlin { jvmToolchain(17) }

dependencies {
  api(project(":livery-protocol"))
  api(project(":livery-client"))
  // TemplateInspector, for `livery build --inspect`: what a document really reads and emits.
  implementation(project(":livery-instantiate"))
  testImplementation(kotlin("test"))
}

application {
  mainClass.set("ee.schimke.livery.publisher.MainKt")
  applicationName = "livery"
}

// Optional commands (LiveryCommand), shipped in the CLI's distribution but not in this library's
// dependencies: `livery ui-builder …`, and `livery review` (which brings the renderer).
val cliExtensions: Configuration by configurations.creating { isCanBeConsumed = false }
dependencies {
  cliExtensions(project(":livery-uibuilder"))
  cliExtensions(project(":livery-review"))
}
tasks.named<CreateStartScripts>("startScripts") { classpath = classpath!! + cliExtensions }
distributions { main { contents { into("lib") { from(cliExtensions) } } } }
tasks.withType<Sync>().configureEach { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
tasks.withType<AbstractArchiveTask>().configureEach { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
