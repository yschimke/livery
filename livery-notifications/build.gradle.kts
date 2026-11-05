plugins {
  alias(libs.plugins.android.library)
}

// Notifications: a Livery template instantiated for a notification's expanded body and handed to
// SystemUI as RemoteViews draw instructions (Android 16+), through rc-players' rc-widget-launcher,
// whose player and tap plumbing notifications share with launcher widgets.
android {
  namespace = "ee.schimke.livery.notifications"
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
