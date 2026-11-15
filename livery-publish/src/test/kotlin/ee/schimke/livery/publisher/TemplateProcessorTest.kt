package ee.schimke.livery.publisher

import ee.schimke.composeai.rcplayer.protocol.RcAnimationSpec
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The catalog build's template processors: by default a template animates only what its author asked to animate
 * ([TemplateProcessor.WithoutDefaultAnimations]); `defaultAnimations: true`, in `catalog.json` or a sidecar, or no
 * processors at all, publishes the `.rc` exactly as it is in the tree.
 */
class TemplateProcessorTest {
  private val sample = File(requireNotNull(System.getProperty("livery.samples")) { "run through Gradle: gradle/sample-catalogs.gradle.kts" }).canonicalFile

  private fun tree(edit: (File) -> Unit = {}): File = Files.createTempDirectory("processors").toFile().also { src ->
    sample.copyRecursively(src)
    edit(src)
  }

  private val pollSource get() = File(sample, "designs/poll-card/in-app.rc").readBytes()

  /** The bytes the catalog built from [src] publishes for poll-card's in-app template. */
  private fun published(src: File, builder: CatalogBuilder = CatalogBuilder(now = { 0 })): ByteArray {
    val out = builder.build(src, Layout.Static, serial = 1)
    val entry = out.manifest.templates.single { it.designId == "poll-card" && it.target.surface == "in-app" }
    return out.files.getValue("blobs/${entry.sha256}.rc").toByteArray().also {
      assertEquals(entry.sha256, okio.ByteString.of(*it).sha256().hex(), "the manifest names the published bytes")
    }
  }

  private fun animationSpecs(bytes: ByteArray) = RcDocumentCodec.decode(bytes).operations.filterIsInstance<RcAnimationSpec>()

  @Test fun byDefaultOnlyWhatTheAuthorAskedForAnimates() {
    assertTrue(animationSpecs(pollSource).isEmpty(), "the sample asks for no animation")
    val specs = animationSpecs(published(tree()))
    assertTrue(specs.isNotEmpty(), "no component was given a disabled spec")
    assertTrue(specs.none { it.isEnabled })
  }

  @Test fun aCatalogCanKeepTheDefaultAnimations() {
    val src = tree { File(it, "catalog.json").let { f -> f.writeText(f.readText().trimEnd().removeSuffix("}").trimEnd() + ",\n  \"defaultAnimations\": true\n}\n") } }
    assertContentEquals(pollSource, published(src))
  }

  @Test fun aTemplateCanKeepTheDefaultAnimations() {
    val src = tree { File(it, "designs/poll-card/in-app.json").let { f -> f.writeText(f.readText().trimEnd().removeSuffix("}").trimEnd() + ",\n  \"defaultAnimations\": true\n}\n") } }
    assertContentEquals(pollSource, published(src))
    // Only that template: another design in the same catalog still has its default animations removed.
    val other = CatalogBuilder(now = { 0 }).build(src, Layout.Static, serial = 1).let { out ->
      val entry = out.manifest.templates.single { it.designId == "banner-classic" && it.target.surface == "in-app" }
      out.files.getValue("blobs/${entry.sha256}.rc").toByteArray()
    }
    assertFalse(animationSpecs(other).isEmpty())
  }

  @Test fun aBuilderWithNoProcessorsPublishesTheTreesBytes() {
    assertContentEquals(pollSource, published(tree(), CatalogBuilder(now = { 0 }, processors = emptyList())))
  }
}
