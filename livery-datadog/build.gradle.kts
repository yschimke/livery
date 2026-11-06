plugins {
  alias(libs.plugins.android.library)
}

// Datadog RUM for Livery, optional: every Livery event as a RUM action or error, so a template tap
// is a TAP action named by its contract action, beside the app's own. The app brings and starts its
// own Datadog SDK; compileOnly, so depending on this adds nothing to an app that does not use it.
android {
  namespace = "ee.schimke.livery.datadog"
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
  compileOnly(libs.datadog.rum)
  testImplementation(libs.datadog.rum)
  testImplementation(kotlin("test-junit"))
}
