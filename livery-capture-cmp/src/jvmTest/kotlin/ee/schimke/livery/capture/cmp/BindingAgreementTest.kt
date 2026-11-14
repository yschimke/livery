package ee.schimke.livery.capture.cmp

import okio.ByteString.Companion.toByteString
import okio.ByteString
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.livery.capture.CapturedTemplate
import ee.schimke.livery.capture.CatalogContracts
import ee.schimke.livery.client.BindResult
import ee.schimke.livery.client.ContractBinder
import ee.schimke.livery.instantiate.TemplateInspector
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.render.RenderSpec
import ee.schimke.livery.render.TemplateRenderer
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking

/**
 * One template, authored through both bindings: the sample designs (`samples/SampleDesigns.kt`)
 * captured here through rc-players' Compose Multiplatform creation API, and by
 * `:livery-capture:captureSamples` through AndroidX's (the same Kotlin, its packages rewritten),
 * must be the same template to Livery. The two writers differ, so the bytes are not compared;
 * what Livery reads from a document is:
 *
 * - **the sidecar**: target, `reads`, `emits` and `source`, byte for byte;
 * - **the document's facts** (`TemplateInspector`): every named value it reads with the Remote
 *   Compose types it is used as, every action it emits, its risky features, and no type problem
 *   against the contract;
 * - **binding**: each of the contract's examples binds (`ContractBinder`), and the names it binds
 *   are ones both documents read or neither does;
 * - **the picture**: both drawn by the Compose player (`livery-render`) with each example's
 *   values, at 1x and 2x, compared pixel by pixel within [MAX_CHANNEL_DELTA] and
 *   [MAX_DIFFERING_PIXELS].
 *
 * Run by `:livery-capture-cmp:bindingAgreementTest` (part of `check`), which writes what it
 * measured to `build/reports/binding-agreement/report.txt`; the same samples as launcher widgets, compared by
 * header and operations under the launcher profile, go to `launcher-report.txt`.
 */
class BindingAgreementTest {
  private class Sample(val catalog: String, val design: String, val contractRef: String, val widthDp: Int, val heightDp: Int, val cmp: suspend (launcher: Boolean) -> CapturedTemplate)

  private val samples = listOf(
    Sample("catalog", "banner-classic", "promo/banner@1", 360, 160, { SampleCatalogs.bannerClassic(it) }),
    Sample("catalog", "poll-card", "social/poll@1", 360, 280, { SampleCatalogs.pollCard(it) }),
    Sample("two-way", "quantity-stepper", "shop/quantity@1", 360, 80, { SampleCatalogs.quantityStepper(it) }),
  )

  private val androidx = File(assertNotNull(System.getProperty("livery.capture.androidx"), "run :livery-capture-cmp:bindingAgreementTest, which captures the AndroidX side first"))
  private val report = StringBuilder()

  @Test fun theBindingsCaptureTheSameTemplate() {
    for (s in samples) {
      val contract = CatalogContracts.contract(File(SampleCatalogs.source, s.catalog), s.contractRef)
      val cmp = runBlocking { s.cmp(false) }
      val folder = File(androidx, "${s.catalog}/designs/${s.design}")
      val androidxBytes = File(folder, "in-app.rc").readBytes().toByteString()
      report.appendLine("${s.catalog}/${s.design} (${s.contractRef})")
      report.appendLine("  bytes: androidx ${androidxBytes.size}, cmp ${cmp.bytes.size}, identical ${androidxBytes == cmp.bytes}")

      report.appendLine("  operations (opcode x count) only or more often in one: androidx ${surplus(androidxBytes, cmp.bytes)}, cmp ${surplus(cmp.bytes, androidxBytes)}")

      assertEquals(File(folder, "in-app.json").readText(), cmp.sidecar(), "${s.design}: the sidecars differ")

      val a = assertNotNull(TemplateInspector.facts(androidxBytes))
      val c = assertNotNull(TemplateInspector.facts(cmp.bytes))
      assertEquals(a.reads, c.reads, "${s.design}: reads")
      assertEquals(a.emits, c.emits, "${s.design}: emits")
      assertEquals(a.types, c.types, "${s.design}: the type each value is read as")
      assertEquals(a.features, c.features, "${s.design}: risky features")
      assertEquals(emptyList(), TemplateInspector.typeProblems(androidxBytes, contract), "${s.design}: AndroidX capture against its contract")
      assertEquals(emptyList(), TemplateInspector.typeProblems(cmp.bytes, contract), "${s.design}: CMP capture against its contract")
      report.appendLine("  facts: reads ${c.reads.sorted()}, emits ${c.emits.sorted()}: equal, as are value types and features; sidecars identical")

      assertTrue(contract.examples.isNotEmpty(), "${s.contractRef} has examples")
      for ((example, data) in contract.examples) {
        val bindings = assertIs<BindResult.Ok>(ContractBinder.bind(contract, data), "${s.contractRef} example $example binds").bindings
        assertEquals(bindings.values.keys intersect a.reads, bindings.values.keys intersect c.reads, "${s.design}/$example: the bound values each document reads")
        for (density in listOf(1f, 2f)) {
          val spec = RenderSpec(s.widthDp, s.heightDp, density, background = 0xFFFFFFFF)
          val fromAndroidx = TemplateRenderer.png(androidxBytes, bindings, spec)
          val fromCmp = TemplateRenderer.png(cmp.bytes, bindings, spec)
          val diff = compare(s, example, density, fromAndroidx, fromCmp)
          report.appendLine("  render $example @${density}x ${spec.widthPx}x${spec.heightPx}: $diff")
          if (diff.maxChannelDelta > MAX_CHANNEL_DELTA || diff.differing > MAX_DIFFERING_PIXELS) {
            flush()
            fail("${s.design}/$example @${density}x draws differently through the two bindings: $diff (allowed: channel delta $MAX_CHANNEL_DELTA, $MAX_DIFFERING_PIXELS pixels)")
          }
        }
      }
    }
    flush()
  }

  /**
   * The same samples as launcher widgets: AndroidX under `widgetsProfile` (`:livery-capture`'s
   * `SampleDesignsTest`), here under `RcCaptureProfiles.launcherWidgets`. Both writers are held to
   * one profile, so the documents must agree on what the profile fixes: the whole header, and the
   * operations they use (as a set: the writers differ in how often, as the report shows). There is
   * no AndroidX player on the JVM to draw the AndroidX capture, so this is the document-level
   * comparison only; `livery-verify`'s CapturedWidgetTest draws both on Android 16's own player.
   */
  @Test fun theBindingsAgreeUnderTheLauncherProfile() {
    for (s in samples) {
      val androidxBytes = File(androidx, "launcher-parity/${s.catalog}-${s.design}.rc").readBytes().toByteString()
      val cmpBytes = runBlocking { s.cmp(true) }.bytes
      val a = RcDocumentCodec.decode(androidxBytes.toByteArray(), RcWireLimits())
      val c = RcDocumentCodec.decode(cmpBytes.toByteArray(), RcWireLimits())
      report.appendLine("${s.catalog}/${s.design} as a launcher widget")
      report.appendLine("  bytes: androidx ${androidxBytes.size}, cmp ${cmpBytes.size}")
      report.appendLine("  operations (opcode x count) only or more often in one: androidx ${surplus(androidxBytes, cmpBytes)}, cmp ${surplus(cmpBytes, androidxBytes)}")
      assertEquals(a.header, c.header, "${s.design}: the launcher header")
      // alpha23's `clickable` also writes an empty AccessibilitySemantics (250), which androidx-main's, and so
      // rc-players', writes only for a role (rc-players' MIGRATING_CREATION.md).
      fun used(d: ee.schimke.composeai.rcplayer.protocol.RcDocument) = d.operations.map { it.opcode }.toSortedSet() - RcOpcodes.ACCESSIBILITY_SEMANTICS
      assertEquals(used(a), used(c), "${s.design}: the operations a launcher capture uses")
      assertTrue(RcOpcodes.CORE_TEXT !in c.operations.map { it.opcode }, "${s.design}: no CoreText on a launcher")
    }
    flush("launcher-report.txt")
  }

  private class Diff(val pixels: Int, val differing: Int, val maxChannelDelta: Int, val inked: Int) {
    override fun toString() = "$differing of $pixels pixels differ, largest channel delta $maxChannelDelta ($inked pixels are not background)"
  }

  private fun compare(s: Sample, example: String, density: Float, androidxPng: ByteString, cmpPng: ByteString): Diff {
    System.getProperty("livery.render.out")?.let { dir ->
      val out = File(dir, "binding-agreement").apply { mkdirs() }
      File(out, "${s.design}-$example-${density}x-androidx.png").writeBytes(androidxPng.toByteArray())
      File(out, "${s.design}-$example-${density}x-cmp.png").writeBytes(cmpPng.toByteArray())
    }
    val a: BufferedImage = ImageIO.read(androidxPng.toByteArray().inputStream())
    val c: BufferedImage = ImageIO.read(cmpPng.toByteArray().inputStream())
    assertEquals(a.width to a.height, c.width to c.height, "${s.design}: render size")
    var differing = 0
    var max = 0
    var inked = 0
    for (y in 0 until a.height) for (x in 0 until a.width) {
      val p = a.getRGB(x, y)
      val q = c.getRGB(x, y)
      if (p != BACKGROUND) inked++
      if (p == q) continue
      differing++
      for (shift in intArrayOf(24, 16, 8, 0)) max = maxOf(max, abs((p ushr shift and 0xFF) - (q ushr shift and 0xFF)))
    }
    // A blank picture would agree with another blank picture.
    assertTrue(inked > a.width * a.height / 20, "${s.design}/$example: the AndroidX capture drew almost nothing ($inked pixels)")
    return Diff(a.width * a.height, differing, max, inked)
  }

  /** The operations [a] holds more of than [b], as `opcode x how many more`: how the two writers differ, for the report. */
  private fun surplus(a: ByteString, b: ByteString): String {
    fun counts(bytes: ByteString) = RcDocumentCodec.decode(bytes.toByteArray(), RcWireLimits()).operations.groupingBy { it.opcode }.eachCount()
    val (ca, cb) = counts(a) to counts(b)
    return ca.mapNotNull { (op, n) -> (n - (cb[op] ?: 0)).takeIf { it > 0 }?.let { "$op x$it" } }.ifEmpty { listOf("none") }.joinToString(", ")
  }

  private fun flush(file: String = "report.txt") {
    print(report)
    System.getProperty("livery.agreement.report")?.let { File(it).apply { mkdirs() }.resolve(file).writeText(report.toString()) }
  }

  private companion object {
    const val BACKGROUND = 0xFFFFFFFF.toInt()

    /** The two captures draw identically in the Compose player: no pixel may differ. */
    const val MAX_CHANNEL_DELTA = 0
    const val MAX_DIFFERING_PIXELS = 0
  }
}
