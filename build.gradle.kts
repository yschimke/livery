import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.compose.multiplatform) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.android.kmp.library) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.maven.publish) apply false
}

// A release sets -Plivery.version (the tag without its v); everything else builds a snapshot.
val liveryVersion = providers.gradleProperty("livery.version").getOrElse("0.1.0-SNAPSHOT")

allprojects {
  group = "ee.schimke.composeai"
  version = liveryVersion
}

/**
 * The libraries an app depends on, published to Maven Central as `ee.schimke.composeai:<module>`.
 * The CLIs (`livery-publish` as `livery`, `livery-mcp`) ship as distributions, but their code is a
 * library too: `livery-gradle-plugin` builds catalogs with `livery-publish`.
 */
val published = mapOf(
  ":livery-protocol" to ("Livery protocol" to "Wire types for server-driven Remote Compose templates: manifests, contracts, designs, targets, decisions, feeds, events"),
  ":livery-client" to ("Livery client" to "Fetch, verify, cache, select and bind Remote Compose templates: Kotlin Multiplatform"),
  ":livery-compose" to ("Livery Compose" to "LiveryTemplate and LiverySlot over rc-players' Compose player: Compose Multiplatform"),
  ":livery-a2ui" to ("Livery A2UI" to "Livery contracts as A2UI components: their catalog schema, and the datasets and events that cross between A2UI and Livery"),
  ":livery-instantiate" to ("Livery instantiate" to "Write a slot's values and images into a template for widget hosts and snapshots"),
  ":livery-launcher-widgets" to ("Livery launcher widgets" to "Android launcher AppWidgets drawn from Livery templates"),
  ":livery-wear-widgets" to ("Livery Wear widgets" to "Glance Wear widgets drawn from Livery templates"),
  ":livery-notifications" to ("Livery notifications" to "Android notification bodies drawn from Livery templates"),
  ":livery-capture" to ("Livery capture" to "Author Livery templates in Compose: contract-typed named values, capture per target, and catalog files that stay current"),
  ":livery-firebase" to ("Livery Firebase" to "Firebase Analytics, Remote Config experiments and Firestore frequency caps for Livery"),
  ":livery-datadog" to ("Livery Datadog" to "Livery events and template taps as Datadog RUM actions and errors"),
  ":livery-sentry" to ("Livery Sentry" to "Livery events as Sentry breadcrumbs, and Sentry's tag on each template slot"),
  ":livery-amplitude" to ("Livery Amplitude" to "Livery events in Amplitude, with experiment arms as user properties"),
  ":livery-render" to ("Livery render" to "Headless PNG renders of Livery templates on the JVM"),
  ":livery-verify" to ("Livery verify" to "Play Livery templates on the players apps ship them to, under Robolectric, and fail on their errors"),
  ":livery-publish" to ("Livery publisher" to "Validate, build and sign Livery catalogs (the livery CLI's library)"),
  ":livery-gradle-plugin" to ("Livery Gradle plugin" to "Builds a Livery catalog into an app's assets at build time"),
)

subprojects {
  val (title, about) = published[path] ?: return@subprojects
  apply(plugin = "com.vanniktech.maven.publish")
  extensions.configure<MavenPublishBaseExtension> {
    publishToMavenCentral(automaticRelease = true)
    if (!version.toString().endsWith("SNAPSHOT")) signAllPublications()
    coordinates("ee.schimke.composeai", name, version.toString())
    pom {
      name.set(title)
      description.set(about)
      url.set("https://github.com/yschimke/livery")
      inceptionYear.set("2026")
      licenses {
        license {
          name.set("The Apache License, Version 2.0")
          url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
          distribution.set("repo")
        }
      }
      developers {
        developer {
          id.set("yschimke")
          name.set("Yuri Schimke")
          url.set("https://github.com/yschimke")
        }
      }
      scm {
        url.set("https://github.com/yschimke/livery")
        connection.set("scm:git:https://github.com/yschimke/livery.git")
        developerConnection.set("scm:git:ssh://git@github.com/yschimke/livery.git")
      }
    }
  }
}
