plugins { `java-platform` }

// The BOM for every library Livery publishes, so an app aligns them with one coordinate:
//
//     implementation(platform("ee.schimke.composeai:livery-bom:<version>"))
//     implementation("ee.schimke.composeai:livery-client")
//     implementation("ee.schimke.composeai:livery-compose")
//
// A release only publishes the modules that changed (release-tasks.gradle.kts), so modules can sit
// at different versions. Each constraint is the version that module is actually published at: the
// same value the release gives its `project.version`, so the BOM and the POMs cannot disagree.
// Kotlin Multiplatform modules are constrained at the coordinate an app names; Gradle module
// metadata resolves the -android / -jvm / -wasm-js / Apple variants from it.

@Suppress("UNCHECKED_CAST")
val versions = rootProject.extra["liveryPublishedVersions"] as Map<String, String>

dependencies {
  constraints {
    versions.filterKeys { it != project.name }.toSortedMap().forEach { (id, version) ->
      api("ee.schimke.composeai:$id:$version")
    }
  }
}
