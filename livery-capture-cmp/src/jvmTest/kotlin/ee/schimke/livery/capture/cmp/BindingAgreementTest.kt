package ee.schimke.livery.capture.cmp

import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
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
 * measured to `build/reports/binding-agreement/report.txt`.
 */
class BindingAgreementTest {
  private class Sample(val catalog: String, val design: String, val contractRef: String, val widthDp: Int, val heightDp: Int, val cmp: suspend () -> CapturedTemplate)

  private val samples = listOf(
    Sample("catalog", "banner-classic", "promo/banner@1", 360, 160, SampleCatalogs::bannerClassic),
    Sample("catalog", "poll-card", "social/poll@1", 360, 280, SampleCatalogs::pollCard),
    Sample("two-way", "quantity-stepper", "shop/quantity@1", 360, 80, SampleCatalogs::quantityStepper),
  )

  private val androidx = File(assertNotNull(System.getProperty("livery.capture.androidx"), "run :livery-capture-cmp:bindingAgreementTest, which captures the AndroidX side first"))
  private val report = StringBuilder()

  @Test fun theBindingsCaptureTheSameTemplate() {
    for (s in samples) {
      val contract = CatalogContracts.contract(File(SampleCatalogs.source, s.catalog), s.contractRef)
      val cmp = runBlocking { s.cmp() }
      val folder = File(androidx, "${s.catalog}/designs/${s.design}")
      val androidxBytes = File(folder, "in-app.rc").readBytes()
      report.appendLine("${s.catalog}/${s.design} (${s.contractRef})")
      report.appendLine("  bytes: androidx ${androidxBytes.size}, cmp ${cmp.bytes.size}, identical ${androidxBytes.contentEquals(cmp.bytes)}")

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

  private class Diff(val pixels: Int, val differing: Int, val maxChannelDelta: Int, val inked: Int) {
    override fun toString() = "$differing of $pixels pixels differ, largest channel delta $maxChannelDelta ($inked pixels are not background)"
  }

  private fun compare(s: Sample, example: String, density: Float, androidxPng: ByteArray, cmpPng: ByteArray): Diff {
    System.getProperty("livery.render.out")?.let { dir ->
      val out = File(dir, "binding-agreement").apply { mkdirs() }
      File(out, "${s.design}-$example-${density}x-androidx.png").writeBytes(androidxPng)
      File(out, "${s.design}-$example-${density}x-cmp.png").writeBytes(cmpPng)
    }
    val a: BufferedImage = ImageIO.read(androidxPng.inputStream())
    val c: BufferedImage = ImageIO.read(cmpPng.inputStream())
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
  private fun surplus(a: ByteArray, b: ByteArray): String {
    fun counts(bytes: ByteArray) = RcDocumentCodec.decode(bytes, RcWireLimits()).operations.groupingBy { it.opcode }.eachCount()
    val (ca, cb) = counts(a) to counts(b)
    return ca.mapNotNull { (op, n) -> (n - (cb[op] ?: 0)).takeIf { it > 0 }?.let { "$op x$it" } }.ifEmpty { listOf("none") }.joinToString(", ")
  }

  private fun flush() {
    print(report)
    System.getProperty("livery.agreement.report")?.let { File(it).apply { mkdirs() }.resolve("report.txt").writeText(report.toString()) }
  }

  private companion object {
    const val BACKGROUND = 0xFFFFFFFF.toInt()

    /** The two captures draw identically in the Compose player: no pixel may differ. */
    const val MAX_CHANNEL_DELTA = 0
    const val MAX_DIFFERING_PIXELS = 0
  }
}
