plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
}

// Authoring templates in Compose (AndroidX remote-creation-compose): named values typed by the
// contract, capture per target, and a design folder that a test keeps current. Used from an app's
// unit tests (Robolectric) or a build-time tool, never at runtime.
android {
  namespace = "ee.schimke.livery.capture"
  compileSdk { version = release(37) { minorApiLevel = 1 } }
  defaultConfig {
    minSdk = 26
    aarMetadata { minCompileSdk = 36 }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

kotlin { jvmToolchain(17) }

dependencies {
  api(project(":livery-client"))
  implementation(project(":livery-instantiate"))
  api(libs.compose.remote.creation.compose)
  api(platform(libs.rc.players.bom))
  api(libs.rc.widget.creation)
  implementation(libs.rc.widget.launcher)

  testImplementation(kotlin("test-junit"))
  testImplementation(libs.robolectric)
  testImplementation(libs.kotlinx.coroutines.core)
}

// Robolectric's SDK, resolved by Gradle so the tests run offline.
val robolectricSdk by configurations.creating {
  isCanBeConsumed = false
  isTransitive = false
}

dependencies { robolectricSdk(libs.robolectric.sdk35) }

val prepareRobolectricSdk by tasks.registering(Sync::class) {
  from(robolectricSdk)
  into(layout.buildDirectory.dir("robolectric-sdk"))
}

tasks.withType<Test>().configureEach {
  dependsOn(prepareRobolectricSdk)
  systemProperty("robolectric.offline", "true")
  systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-sdk").get().asFile.absolutePath)
}
