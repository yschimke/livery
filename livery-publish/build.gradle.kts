plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  application
}

kotlin { jvmToolchain(17) }

dependencies {
  implementation(project(":livery-protocol"))
  implementation(project(":livery-client"))
  testImplementation(kotlin("test"))
}

application {
  mainClass.set("ee.schimke.livery.publisher.MainKt")
  applicationName = "livery"
}
