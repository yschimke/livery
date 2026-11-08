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
      // To give a test document a data-bound image.
      implementation(libs.rc.player.protocol)
    }
  }
}

// Ship the production link through Kotlin's pinned Binaryen toolchain.
val webLink = tasks.named<org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrLink>("compileProductionLibraryKotlinWasmJs")
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
val optimizeWebWasm = org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenExec.register(
  kotlin.targets.getByName("wasmJs").compilations.getByName("main") as org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrCompilation,
  "optimizeWebWasm",
) {
  inputFiles.from(webLink.flatMap { it.destinationDirectory }.map { it.file("livery-livery-web.wasm") })
  outputDirectory.set(layout.buildDirectory.dir("optimized-web"))
}

val webDist = tasks.register<Sync>("webDist") {
  from(webLink.flatMap { it.destinationDirectory }) { include("*.mjs", "*.d.mts") }
  from(optimizeWebWasm.flatMap { it.outputDirectory }) { include("*.wasm") }
  into(layout.buildDirectory.dir("web-dist"))
}
