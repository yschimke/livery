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
      // Platform-neutral: Skia's natives for every desktop platform come with Compose's
      // skiko-awt-runtime-all, so the published library (and livery-mcp's zip, built on Linux)
      // renders on Linux, macOS and Windows. Never compose.desktop.currentOs here: it would publish
      // the build machine's platform as everyone's.
      api(compose.desktop.common)
    }
    jvmTest.dependencies {
      implementation(kotlin("test"))
      implementation(compose.desktop.currentOs)
    }
  }
}

// -Plivery.render.out=<dir> keeps the test renders, to look at them.
tasks.withType<Test>().configureEach {
  providers.gradleProperty("livery.render.out").orNull?.let { systemProperty("livery.render.out", it) }
}
