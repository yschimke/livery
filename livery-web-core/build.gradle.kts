plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
}

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

// The host-neutral Wasm module and JavaScript glue shipped by web/core.
val webDist by tasks.registering(Sync::class) {
  val link = tasks.named<org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrLink>("compileProductionLibraryKotlinWasmJs")
  from(link.flatMap { it.destinationDirectory })
  include("*.mjs", "*.wasm", "*.d.mts")
  into(layout.buildDirectory.dir("web-dist"))
}

// The tests read the built sample catalogs (their captured designs are build output).
apply(from = rootProject.file("gradle/sample-catalogs.gradle.kts"))
