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
