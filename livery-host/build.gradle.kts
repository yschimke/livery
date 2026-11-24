import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar

plugins {
  alias(libs.plugins.kotlin.jvm)
  application
}

kotlin { jvmToolchain(17) }

application {
  applicationName = "livery-host"
  mainClass.set("ee.schimke.livery.host.LiveryHostMainKt")
}

tasks.named<Tar>("distTar") {
  compression = Compression.GZIP
  archiveExtension.set("tar.gz")
}

dependencies {
  api(project(":livery-server"))
  implementation(libs.ktor.server.cio)
  implementation(libs.ktor.server.auto.head.response)

  testImplementation(kotlin("test"))
}
