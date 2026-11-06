plugins {
  alias(libs.plugins.android.library)
}

// Amplitude for Livery, optional: Livery events as Amplitude events in AnalyticsEvents' shape, and each
// exposure's arm as a user property. The app brings and initialises its own Amplitude SDK; compileOnly,
// so depending on this adds nothing it does not use.
android {
  namespace = "ee.schimke.livery.amplitude"
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
  compileOnly(libs.amplitude.android)
  testImplementation(kotlin("test-junit"))
}
