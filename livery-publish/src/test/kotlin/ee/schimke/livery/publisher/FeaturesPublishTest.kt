package ee.schimke.livery.publisher

import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcShaderData
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PROTOCOL.md §5.1 in the publisher: a template's risky features are read from the document itself
 * and recorded in the manifest, and a catalog with any needs a client that gates on them (level 8).
 */
class FeaturesPublishTest {
  private val sample = File(System.getProperty("user.dir")).resolve("../samples/catalog").canonicalFile

  @Test fun theSampleUsesNoRiskyFeature() {
    val m = CatalogBuilder(now = { 0 }).build(sample, Layout.Static, serial = 1).manifest
    assertTrue(m.templates.all { it.features.isEmpty() }, m.templates.filter { it.features.isNotEmpty() }.toString())
    assertTrue(m.minClient < FEATURES_LEVEL)
  }

  @Test fun aShaderIsRecordedFromTheDocumentAndRaisesTheLevel() {
    val tree = Files.createTempDirectory("features").toFile().also { sample.copyRecursively(it) }
    val rc = File(tree, "designs/tile-default/in-app.rc")
    val d = RcDocumentCodec.decode(rc.readBytes(), RcWireLimits())
    rc.writeBytes(RcDocumentCodec.encode(d.copy(operations = listOf(RcTextData(9001, "half4 main(float2 p) { return half4(1); }"), RcShaderData(9002, 9001, emptyMap(), emptyMap(), emptyMap())) + d.operations)))
    val m = CatalogBuilder(now = { 0 }).build(tree, Layout.Static, serial = 1).manifest
    assertEquals(listOf("shaders"), m.templates.single { it.designId == "tile-default" && it.target.surface == "in-app" }.features)
    assertEquals(FEATURES_LEVEL, m.minClient, "an older client would play the shader where no app allowed it")
  }
}
