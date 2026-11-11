plugins {
  alias(libs.plugins.kotlin.multiplatform)
}

// What a capture is, whichever creation API made it: a contract's names, types and defaults as an
// author asks for them (ContractChecks), a captured template with the sidecar read from its own
// document (CapturedTemplate), and, on the JVM and Android, a catalog source tree's contracts and
// design folders as files (CatalogContracts, DesignFolder). No AndroidX and no rc-creation here:
// the bindings are livery-capture (AndroidX remote-creation-compose) and livery-capture-cmp
// (rc-players' Compose Multiplatform creation API).
kotlin {
  jvmToolchain(17)
  // The JVM variant is also what an Android module resolves: there is nothing Android in it.
  jvm()
  // Compiled for the browser; its tests are the JVM ones (no headless browser in CI).
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser { testTask { enabled = false } } }

  sourceSets {
    commonMain.dependencies {
      api(project(":livery-client"))
      // TemplateInspector: the sidecar's reads and emits come from the document.
      implementation(project(":livery-instantiate"))
    }
    jvmTest.dependencies { implementation(kotlin("test")) }
  }
}
