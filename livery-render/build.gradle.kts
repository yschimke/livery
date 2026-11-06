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
      // Platform-neutral: Skia's natives for every desktop platform, so the published library (and
      // livery-mcp's zip, built on Linux) renders on Linux, macOS and Windows. Never
      // compose.desktop.currentOs here: it would publish the build machine's platform as
      // everyone's. Compose 1.13 brings them all as skiko-awt-runtime-all; on the stable 1.12 line
      // they are listed per platform, at the skiko version Compose resolves.
      api(compose.desktop.common)
      listOf("linux-x64", "linux-arm64", "macos-x64", "macos-arm64", "windows-x64").forEach {
        runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-$it:${libs.versions.skiko.get()}")
      }
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
