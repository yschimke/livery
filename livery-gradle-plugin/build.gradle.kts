plugins {
  alias(libs.plugins.kotlin.jvm)
  `java-gradle-plugin`
}

// Builds a Livery catalog source tree into an app's assets at build time: the copy a fresh install
// plays before it has downloaded anything (TemplateRepository.Bundled).
kotlin { jvmToolchain(17) }

gradlePlugin {
  plugins {
    create("liveryCatalog") {
      id = "ee.schimke.livery.catalog"
      implementationClass = "ee.schimke.livery.gradle.LiveryCatalogPlugin"
      displayName = "Livery catalog"
      description = "Builds a Livery catalog into an Android app's assets"
    }
  }
}

dependencies {
  implementation(project(":livery-publish"))
  // Only the variant API; the app's own Android Gradle Plugin is the one on the classpath.
  compileOnly(libs.android.gradle.api)
  testImplementation(kotlin("test"))
  testImplementation(gradleTestKit())
}
