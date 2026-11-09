plugins {
  alias(libs.plugins.kotlin.jvm)
  `java-library`
}

kotlin { jvmToolchain(17) }

dependencies {
  api(libs.ktor.server.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.core)
  testImplementation(kotlin("test"))
  testImplementation(libs.ktor.server.test.host)
}

tasks.test {
  // The installer and JVM store consume the same experimental publication-format cases.
  inputs.file(rootProject.file("samples/component-preview/publication-cases.json"))
  systemProperty("livery.publication.cases", rootProject.file("samples/component-preview/publication-cases.json").absolutePath)
}
