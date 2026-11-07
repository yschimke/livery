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
