package ee.schimke.livery.capture

import java.io.File

/**
 * Where this binding's captures of the sample designs go: `:livery-capture:captureSamples` runs
 * [SampleDesignsTest] with `livery.capture.out` set, for `:livery-capture-cmp:bindingAgreementTest`
 * to compare with rc-players' captures of the same Kotlin. In the ordinary unit tests the property
 * is unset, and a sample is captured and checked but not written. (The sample catalogs themselves
 * are built from the Compose Multiplatform captures: `:livery-capture-cmp:sampleCatalogs`.)
 */
internal object SampleCatalogs {
  fun write(catalog: String, design: String, templates: Map<String, CapturedTemplate>) {
    val out = System.getProperty("livery.capture.out") ?: return
    DesignFolder(File(out, "$catalog/designs/$design")).write(templates)
  }
}
