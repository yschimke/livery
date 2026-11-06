plugins {
  alias(libs.plugins.android.library)
}

// Firebase for Livery, all optional: Analytics as an AnalyticsSink, Remote Config (and so Firebase
// A/B Testing) as an ExperimentAssigner, and Firestore as a FrequencyBackend. The app brings its own
// Firebase project (google-services.json); this module only adapts Livery's interfaces to it.
android {
  namespace = "ee.schimke.livery.firebase"
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
  // compileOnly: each adapter touches only its own Firebase library, so an app adds just the ones it
  // uses (FirebaseAnalyticsSink: firebase-analytics; RemoteConfigAssigner: firebase-config;
  // FirestoreFrequencyBackend: firebase-firestore), from its own Firebase BOM.
  compileOnly(platform(libs.firebase.bom))
  compileOnly(libs.firebase.analytics)
  compileOnly(libs.firebase.config)
  compileOnly(libs.firebase.firestore)
  implementation(libs.kotlinx.coroutines.play.services)
}
