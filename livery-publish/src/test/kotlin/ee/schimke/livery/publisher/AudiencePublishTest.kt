package ee.schimke.livery.publisher

import ee.schimke.livery.protocol.Audience
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PROTOCOL.md §2.3 in the publisher: a design's `audience` reaches the manifest from its
 * `design.json`, and a catalog with one needs a client that honours it (level 9).
 */
class AudiencePublishTest {
  private val sample = File(requireNotNull(System.getProperty("livery.samples")) { "run through Gradle: gradle/sample-catalogs.gradle.kts" }).canonicalFile

  @Test fun theSampleHasNoAudience() {
    val m = CatalogBuilder(now = { 0 }).build(sample, Layout.Static, serial = 1).manifest
    assertTrue(m.designs.all { it.audience.isEmpty() })
    assertTrue(m.minClient < AUDIENCE_LEVEL)
  }

  @Test fun anAudienceIsPublishedAndRaisesTheLevel() {
    val tree = Files.createTempDirectory("audience").toFile().also { sample.copyRecursively(it) }
    File(tree, "designs/tile-default/design.json").writeText(
      """{ "id": "tile-default", "contract": "live/status-tile@1", "title": "Built-in", "install": "Always",
        |  "audience": [ { "match": { "device.model": "X1*" }, "priority": 3 }, { "match": { "device.integration": "acme" } } ] }""".trimMargin(),
    )
    val m = CatalogBuilder(now = { 0 }).build(tree, Layout.Static, serial = 1).manifest
    assertEquals(
      listOf(Audience(mapOf("device.model" to "X1*"), 3), Audience(mapOf("device.integration" to "acme"))),
      m.designs.single { it.id == "tile-default" }.audience,
    )
    assertEquals(AUDIENCE_LEVEL, m.minClient, "an older client would draw a design made for one device for everyone")
  }
}
