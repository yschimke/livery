plugins {
  alias(libs.plugins.android.library)
}

// Launcher AppWidgets: Livery templates instantiated per widget size and handed to the launcher as
// RemoteViews draw instructions, through rc-players' rc-widget-launcher.
android {
  namespace = "ee.schimke.livery.widgets.launcher"
  compileSdk { version = release(37) { minorApiLevel = 1 } }
  defaultConfig {
    minSdk = 26
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
  implementation(platform(libs.rc.players.bom))
  implementation(libs.rc.widget.launcher)
  implementation(libs.kotlinx.coroutines.core)
}
