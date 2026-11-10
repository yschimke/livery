// Applied by every module whose tests read the sample catalogs:
//   apply(from = rootProject.file("gradle/sample-catalogs.gradle.kts"))
//
// The samples' Compose-authored designs are Kotlin (livery-capture's Sample*Test); their .rc and
// sidecars are build output, so a test reads the built trees, never samples/ itself:
//   System.getProperty("livery.samples")          the built samples/catalog
//   System.getProperty("livery.samples.two-way")  the built samples/two-way
val sampleCatalogs = configurations.create("sampleCatalogs") {
  isCanBeConsumed = false
  isCanBeResolved = true
}

dependencies.add(sampleCatalogs.name, dependencies.project(mapOf("path" to ":livery-capture", "configuration" to "sampleCatalogsElements")))

tasks.withType<Test>().configureEach {
  val samples: FileCollection = sampleCatalogs
  inputs.files(samples).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("sampleCatalogs")
  doFirst {
    val root = samples.singleFile
    systemProperty("livery.samples", root.resolve("catalog").absolutePath)
    systemProperty("livery.samples.two-way", root.resolve("two-way").absolutePath)
  }
}
