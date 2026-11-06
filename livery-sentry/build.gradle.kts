plugins {
  alias(libs.plugins.android.library)
}

// Sentry for Livery, optional: Livery events as breadcrumbs (a tap as a user `ui.click` crumb), and
// SentryCompose.slotModifier, Sentry's own tag on each template slot for its tap tracking. The app brings
// and starts its own Sentry SDK; compileOnly, so depending on this adds nothing it does not use.
android {
  namespace = "ee.schimke.livery.sentry"
  compileSdk { version = release(37) { minorApiLevel = 1 } }
  defaultConfig {
    minSdk = 26
    aarMetadata { minCompileSdk = 36 }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin { jvmToolchain(17) }

dependencies {
  api(project(":livery-client"))
  compileOnly(libs.sentry)
  // Only for SentryCompose.slotModifier, in a Compose app with sentry-compose.
  compileOnly(libs.sentry.compose)
  compileOnly(libs.cmp.ui)
  testImplementation(libs.sentry)
  testImplementation(kotlin("test-junit"))
}
