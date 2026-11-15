package ee.schimke.livery.publisher

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A catalog build is reproducible: one source tree, built twice at one `SOURCE_DATE_EPOCH`, writes
 * the same files byte for byte — the blobs always (they are the captured `.rc` bytes, content
 * addressed), and the manifest once its clock is fixed.
 */
class ReproducibleBuildTest {
  private val sample = File(requireNotNull(System.getProperty("livery.samples")) { "run through Gradle: gradle/sample-catalogs.gradle.kts" }).canonicalFile

  @Test fun twoBuildsAtOneSourceDateEpochAreIdentical() {
    val clock = buildClock("1700000000")
    val first = CatalogBuilder(now = clock).build(sample, Layout.Static)
    Thread.sleep(5)
    val second = CatalogBuilder(now = clock).build(sample, Layout.Static)
    assertEquals(first.manifestBytes, second.manifestBytes)
    assertEquals(first.files.keys, second.files.keys)
    first.files.forEach { (path, bytes) -> assertEquals(bytes, second.files[path], path) }
    assertEquals(1_700_000_000_000, first.manifest.serial)
    assertEquals(1_700_000_000_000, first.manifest.generatedAtEpochMs)
  }

  @Test fun withoutSourceDateEpochTheClockIsTheWallClock() {
    val before = System.currentTimeMillis()
    val now = buildClock(null)()
    assert(now >= before)
    assertEquals(buildClock("")().let { it >= before }, true)
  }

  @Test fun aMalformedSourceDateEpochIsAnError() {
    assertFailsWith<IllegalArgumentException> { buildClock("yesterday") }
  }
}
