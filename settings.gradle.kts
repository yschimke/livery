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
