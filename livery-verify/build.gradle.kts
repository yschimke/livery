plugins {
  alias(libs.plugins.android.library)
}

// Plays templates on the players apps ship them to, under Robolectric, and fails on what a device
// would show as an error. First: Android 16's own launcher widget player (RemoteViews draw
// instructions), which catches a template's exception, sets its private `mDisable` and draws a
// warning sign instead. Used from an app's unit tests, never at runtime.
android {
  namespace = "ee.schimke.livery.verify"
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
  implementation(platform(libs.rc.players.bom))
  implementation(libs.rc.widget.launcher)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(kotlin("test-junit"))
  testImplementation(libs.robolectric)
}

// Robolectric's Android 16 SDK, resolved by Gradle so the tests run offline.
val robolectricSdk by configurations.creating {
  isCanBeConsumed = false
  isTransitive = false
}

dependencies { robolectricSdk(libs.robolectric.sdk36) }

val prepareRobolectricSdk by tasks.registering(Sync::class) {
  from(robolectricSdk)
  into(layout.buildDirectory.dir("robolectric-sdk"))
}

tasks.withType<Test>().configureEach {
  // Robolectric runs Android 16 only on Java 21; the library itself stays Java 17 bytecode.
  javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
  dependsOn(prepareRobolectricSdk)
  systemProperty("robolectric.offline", "true")
  systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-sdk").get().asFile.absolutePath)
  // Robolectric's Android 16 shares memory through FileDescriptor internals JDK 17+ hides.
  jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}
