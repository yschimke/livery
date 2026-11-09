plugins {
  kotlin("jvm") version "2.3.21"
  id("org.jetbrains.dokka") version "2.3.0"
}

version = "fixture-1"
kotlin { jvmToolchain(17) }

// @sample displays Kotlin source. Playing the captured RC document does not compile or
// execute this source, and needs no Kotlin Playground service.
configurations.configureEach {
  exclude(group = "org.jetbrains.dokka", module = "kotlin-playground-samples-plugin")
}

dokka {
  dokkaGeneratorIsolation.set(ClassLoaderIsolation())
  dokkaPublications.html {
    moduleName.set("Remote Compose preview example")
    moduleVersion.set(project.version.toString())
    outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
    offlineMode.set(true)
    failOnWarning.set(true)
  }
  dokkaSourceSets.named("main") {
    samples.from("src/samples/kotlin/QuantitySamples.kt")
    reportUndocumented.set(true)
    jdkVersion.set(17)
  }
}
