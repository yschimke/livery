plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
}

// Livery in an A2UI surface on the web, without a TypeScript port: the common LiveryA2uiWeb (the
// same selection, trust, binding, instantiation and action gate every client runs), compiled to
// Wasm with a small JSON API exported to JavaScript. `@livery/a2ui` (web/a2ui) draws with it.
// The JVM target is for its tests.
kotlin {
  jvmToolchain(17)
  jvm()
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs {
    browser { testTask { enabled = false } }
    binaries.library()
    generateTypeScriptDefinitions()
  }

  sourceSets {
    commonMain.dependencies {
      api(project(":livery-client"))
      api(project(":livery-instantiate"))
      api(project(":livery-a2ui"))
    }
    jvmTest.dependencies {
      implementation(kotlin("test"))
      implementation(libs.kotlinx.coroutines.test)
      implementation(project(":livery-publish"))
    }
  }
}

// The Wasm module and its JavaScript glue, for `@livery/a2ui` to ship in its dist/livery-web/.
val webDist by tasks.registering(Sync::class) {
  val link = tasks.named<org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrLink>("compileProductionLibraryKotlinWasmJs")
  from(link.flatMap { it.destinationDirectory })
  include("*.mjs", "*.wasm", "*.d.mts")
  into(layout.buildDirectory.dir("web-dist"))
}
