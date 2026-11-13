// Flings the same feed of sample cards drawn two ways, Livery templates and their Compose UI twins,
// and records each with Macrobenchmark's FrameTimingMetric:
//
//   ./gradlew :livery-benchmark:connectedBenchmarkReleaseAndroidTest
//
// against a connected device or emulator (on an emulator, add
// -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR). CI runs it
// on an emulator in `.github/workflows/benchmarks.yml`: a rough comparison, not a device number.
// Build-only; never published.
plugins {
  alias(libs.plugins.android.test)
  alias(libs.plugins.androidx.baselineprofile)
}

android {
  namespace = "ee.schimke.livery.benchmark"
  compileSdk { version = release(37) { minorApiLevel = 1 } }
  defaultConfig {
    minSdk = 28
    targetSdk = 37
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  targetProjectPath = ":livery-baselineprofile-host"
}

kotlin { jvmToolchain(17) }

// Only the plugin's benchmark variant: minified like a release, profileable, and with the
// libraries' baseline profiles installed.
androidComponents { beforeVariants { it.enable = it.buildType == "benchmarkRelease" } }

baselineProfile { useConnectedDevices = true }

dependencies {
  implementation(libs.androidx.test.ext.junit)
  implementation(libs.androidx.test.uiautomator)
  implementation(libs.androidx.benchmark.macro.junit4)
}
