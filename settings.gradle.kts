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
  }
}

include(":livery-protocol")
include(":livery-client")
include(":livery-publish")
include(":livery-compose")
include(":livery-instantiate")
include(":livery-launcher-widgets")
include(":livery-wear-widgets")
include(":livery-notifications")
include(":livery-firebase")
include(":livery-datadog")
include(":livery-sentry")
include(":livery-amplitude")
include(":livery-capture")
include(":livery-render")
include(":livery-mcp")
include(":livery-uibuilder")
include(":livery-review")
include(":livery-verify")
include(":livery-gradle-plugin")
