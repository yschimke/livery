plugins {
  alias(libs.plugins.android.library)
  // Glance Wear's empty-container capture, whose header a widget's document takes, is composable.
  alias(libs.plugins.compose.compiler)
}

// Glance Wear widgets: Livery templates instantiated per widget container on the watch, through
// rc-players' rc-widget-core Wear frames and Glance Wear's raw-content path.
android {
  namespace = "ee.schimke.livery.widgets.wear"
  compileSdk { version = release(37) { minorApiLevel = 1 } }
  defaultConfig {
    minSdk = 30
    // Built against 37.1 for its dependencies; nothing here needs more than 36 of an app.
    aarMetadata { minCompileSdk = 36 }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin { jvmToolchain(17) }

dependencies {
  api(project(":livery-instantiate"))
  api(libs.glance.wear)
  api(libs.glance.wear.core)
  implementation(platform(libs.rc.players.bom))
  implementation(libs.rc.player.edit)
  implementation(libs.rc.player.protocol)
  implementation(libs.kotlinx.coroutines.core)
}
