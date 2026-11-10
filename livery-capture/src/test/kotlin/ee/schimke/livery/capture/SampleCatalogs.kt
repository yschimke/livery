package ee.schimke.livery.capture

import java.io.File

/**
 * Where the sample designs go. The repository commits their Kotlin, never their `.rc`:
 * `:livery-capture:captureSamples` runs the `Sample*Test`s with `livery.capture.out` set, and
 * `:livery-capture:sampleCatalogs` lays the captures over `samples/<catalog>/` in
 * `livery-capture/build/samples/`, which every test, the CLI checks and CI read. In the ordinary
 * unit tests the property is unset, and a sample is captured and checked but not written.
 */
internal object SampleCatalogs {
  fun write(catalog: String, design: String, templates: Map<String, CapturedTemplate>) {
    val out = System.getProperty("livery.capture.out") ?: return
    DesignFolder(File(out, "$catalog/designs/$design")).write(templates)
  }
}
