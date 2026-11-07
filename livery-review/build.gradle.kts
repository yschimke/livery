plugins {
  alias(libs.plugins.kotlin.jvm)
}

// `livery review`: the review report with pictures. It renders each design a change touches with
// its contract's examples, before and after, through livery-render (Compose and Skia), so it is a
// CLI extension (a LiveryCommand the `livery` distribution puts on its class path) rather than part
// of livery-publish, which stays free of a renderer.
kotlin { jvmToolchain(17) }

dependencies {
  compileOnly(project(":livery-publish"))
  implementation(project(":livery-render"))
  // TemplateInspector: which templates are Remote Compose documents at all (a sample can be a
  // placeholder), so one that decodes is the one drawn.
  implementation(project(":livery-instantiate"))
  testImplementation(project(":livery-publish"))
  testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
  providers.gradleProperty("livery.render.out").orNull?.let { systemProperty("livery.render.out", it) }
}

// The Android SDK jar `livery play --surface launcher-widget` has Robolectric play on: the one the
// bundled Robolectric expects, named here from the version catalog so the two never drift.
val playProperties by tasks.registering {
  val sdk = libs.versions.robolectric.sdk36.get()
  val out = layout.buildDirectory.dir("generated/play")
  inputs.property("sdk", sdk)
  outputs.dir(out)
  doLast { out.get().asFile.resolve("livery-play.properties").apply { parentFile.mkdirs(); writeText("robolectric.sdk36=$sdk\n") } }
}
sourceSets.main { resources.srcDir(playProperties) }
