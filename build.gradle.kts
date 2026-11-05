plugins {
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.compose.multiplatform) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.android.kmp.library) apply false
  alias(libs.plugins.android.library) apply false
}

allprojects {
  group = "ee.schimke.livery"
  version = "0.1.0-SNAPSHOT"
}
