rootProject.name = "livery"

pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    google()
  }
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    google()
    // The Compose Multiplatform port of A2UI (yschimke/a2ui-catalog's vendor/), for
    // livery-a2ui-compose's JVM target: a static Maven repository, and only for that group.
    maven("https://raw.githubusercontent.com/yschimke/a2ui-catalog-out/a2ui-cmp-maven") {
      content { includeGroup("ee.schimke.a2uicmp") }
    }
  }
}

include(":livery-protocol")
include(":livery-client")
include(":livery-catalog")
include(":livery-publish")
include(":livery-server")
include(":livery-compose")
include(":livery-compose-material3")
include(":livery-a2ui")
include(":livery-a2ui-compose")
include(":livery-web")
include(":livery-web-core")
include(":livery-web-compose")
include(":livery-instantiate")
include(":livery-launcher-widgets")
include(":livery-wear-widgets")
include(":livery-notifications")
include(":livery-firebase")
include(":livery-datadog")
include(":livery-sentry")
include(":livery-amplitude")
include(":livery-capture-core")
include(":livery-capture-cmp")
include(":livery-capture")
include(":livery-render")
include(":livery-mcp-core")
include(":livery-mcp")
include(":livery-uibuilder")
include(":livery-review")
include(":livery-verify")
include(":livery-gradle-plugin")
include(":livery-bom")
// Baseline profiles for the published Android libraries: an app that plays the sample catalog, and
// the generator that flings it. Neither is published. See livery-baselineprofile/README.md.
include(":livery-baselineprofile-host")
project(":livery-baselineprofile-host").projectDir = file("livery-baselineprofile/host")
include(":livery-baselineprofile")
project(":livery-baselineprofile").projectDir = file("livery-baselineprofile/generator")
