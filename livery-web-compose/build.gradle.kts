plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

// Optional browser renderer. No selection, trust or action policy crosses into this module.
kotlin {
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs {
    browser { testTask { enabled = false } }
    binaries.library()
    generateTypeScriptDefinitions()
  }
  sourceSets.wasmJsMain.dependencies {
    implementation(project.dependencies.platform(libs.rc.players.bom))
    implementation(libs.rc.player.compose)
    implementation(libs.cmp.ui)
    implementation(libs.cmp.foundation)
    implementation(libs.cmp.runtime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
  }
}

tasks.matching { it.name == "checkComposeUiTestConfigurationForWasmJs" }.configureEach { enabled = false }

val webDist by tasks.registering(Sync::class) {
  val link = tasks.named<org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrLink>("compileProductionLibraryKotlinWasmJs")
  dependsOn("processSkikoRuntimeForKWasm")
  from(link.flatMap { it.destinationDirectory }) { include("*.mjs", "*.wasm", "*.d.mts") }
  from(layout.buildDirectory.dir("compose/skiko-runtime-processed-wasmjs")) { include("skiko.mjs", "skiko.wasm") }
  from("fonts") { into("fonts") }
  into(layout.buildDirectory.dir("web-dist"))
}
