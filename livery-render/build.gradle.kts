plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

// Headless renders of templates to PNG on the JVM, through rc-players' Compose player on Skia:
// what an agent, a CI preview step or a picker's preview generator looks at.
kotlin {
  jvmToolchain(17)
  jvm()
  sourceSets {
    jvmMain.dependencies {
      api(project(":livery-compose"))
      // Skia's native library for the machine this runs on (CI, a developer's laptop, a server).
      implementation(compose.desktop.currentOs)
    }
    jvmTest.dependencies { implementation(kotlin("test")) }
  }
}

// -Plivery.render.out=<dir> keeps the test renders, to look at them.
tasks.withType<Test>().configureEach {
  providers.gradleProperty("livery.render.out").orNull?.let { systemProperty("livery.render.out", it) }
}
