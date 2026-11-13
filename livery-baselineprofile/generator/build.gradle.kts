import com.android.build.api.dsl.ManagedVirtualDevice

// Collects the baseline profile livery-protocol, livery-client and livery-compose ship in their
// AARs, by flinging `:livery-baselineprofile-host` through the sample catalog:
//
//   ./gradlew :livery-protocol:generateBaselineProfile :livery-client:generateBaselineProfile \
//     :livery-compose:generateBaselineProfile
//
// against a connected emulator (API 33+), or add -Pbaselineprofile.gmd=true to let Gradle boot one.
// CI does the same in `.github/workflows/baseline-profiles.yml`. Build-only; never published.
plugins {
  alias(libs.plugins.android.test)
  alias(libs.plugins.androidx.baselineprofile)
}

android {
  namespace = "ee.schimke.livery.baselineprofile"
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
  testOptions.managedDevices.allDevices {
    create("pixel6Api34", ManagedVirtualDevice::class.java) {
      device = "Pixel 6"
      apiLevel = 34
      systemImageSource = "aosp"
    }
  }
}

kotlin { jvmToolchain(17) }

// Only the plugin's profile-capture variant; the debug and release test variants have nothing to run.
androidComponents { beforeVariants { it.enable = it.buildType == "nonMinifiedRelease" } }

baselineProfile {
  val gmd = providers.gradleProperty("baselineprofile.gmd").orNull == "true"
  if (gmd) managedDevices += "pixel6Api34"
  useConnectedDevices = !gmd
}

dependencies {
  implementation(libs.androidx.test.ext.junit)
  implementation(libs.androidx.test.uiautomator)
  implementation(libs.androidx.benchmark.macro.junit4)
}
