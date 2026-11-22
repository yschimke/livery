plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

// Compose UI Builder documents (`.uid`) captured as Livery templates locally, with no UI Builder
// server: a generic interpreter for the document's node tree (common), drawn with the components an
// app registers for its UI Builder catalog, its host inputs bound through LiveryValues so a name or
// type the contract doesn't declare fails the capture. JVM: the catalog tree capture and the entry
// point livery-gradle-plugin's `capture { uiBuilder() }` runs.
kotlin {
  jvmToolchain(17)
  jvm()

  sourceSets {
    commonMain.dependencies {
      api(project(":livery-capture-cmp"))
      api(libs.kotlinx.serialization.json)
      implementation(libs.cmp.runtime)
    }
    jvmMain.dependencies {
      implementation(libs.kotlinx.coroutines.core)
    }
    jvmTest.dependencies {
      implementation(kotlin("test"))
      implementation(project(":livery-instantiate"))
      implementation(project(":livery-catalog"))
    }
  }
}
