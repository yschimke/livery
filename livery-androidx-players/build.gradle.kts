plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
}

// AndroidX's own Remote Compose players behind Livery's LiveryPlayer: the View player and the embedded
// (pure Compose) one. Alpha artifacts, so this module is not in the published list (docs/PLAYERS.md).
android {
  namespace = "ee.schimke.livery.androidx"
  compileSdk { version = release(37) { minorApiLevel = 1 } }
  defaultConfig {
    minSdk = 29
    aarMetadata { minCompileSdk = 36 }
  }
  // The players' entry points are @RestrictTo(LIBRARY_GROUP) while they are alpha: this module is the adapter that uses them.
  lint { disable += "RestrictedApi" }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin { jvmToolchain(17) }

dependencies {
  api(project(":livery-compose"))
  implementation(libs.compose.remote.core)
  implementation(libs.compose.remote.player.core)
  implementation(libs.compose.remote.player.view)
  implementation(libs.compose.remote.player.compose)
  // forDisplay: the document rewritten for the display (density hardcoded, constants folded) the View player is handed.
  implementation(platform(libs.rc.players.bom))
  implementation(libs.rc.player.edit)
  implementation(libs.rc.player.protocol)
  implementation(libs.cmp.ui)
  implementation(libs.cmp.runtime)
  implementation(libs.cmp.foundation)
}
