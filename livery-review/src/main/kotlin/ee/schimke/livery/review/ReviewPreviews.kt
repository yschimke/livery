package ee.schimke.livery.review

import ee.schimke.livery.client.BindResult
import ee.schimke.livery.client.ContractBinder
import ee.schimke.livery.instantiate.TemplateInspector
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.TemplateEntry
import ee.schimke.livery.publisher.CatalogBuilder
import ee.schimke.livery.publisher.CompatibilityCheck
import ee.schimke.livery.publisher.CompatibilityCheck.DesignPreview
import ee.schimke.livery.render.RenderSpec
import ee.schimke.livery.render.TemplateRenderer
import java.io.File

/**
 * The review's pictures: every design a change adds, changes or removes, drawn with each of its
 * contract's examples before and after, as PNGs in [out]. The data is the contract's own examples,
 * so the same picture means the same pixels: an unchanged render is said so, and a change in a
 * template's bytes that draws the same (an optimisation) reads as one.
 *
 * One template per design: the one covering [size] when given (both before and after), else the
 * one with the largest smallest size, drawn at that smallest size, where it has the most room.
 */
class ReviewPreviews(
  private val out: File,
  /** Prefix for the image paths in the report: a URL the PNGs will be served at, or relative. */
  private val imageBase: String = "",
  private val size: Pair<Int, Int>? = null,
  private val density: Float = 2f,
  private val maxExamples: Int = 4,
  private val render: (ByteArray, ee.schimke.livery.client.Bindings, RenderSpec) -> ByteArray = { rc, b, spec -> TemplateRenderer.png(rc, b, spec) },
) {
  fun render(c: CompatibilityCheck.Comparison): List<DesignPreview> {
    out.mkdirs()
    val touched = c.report.templates.filter { it.status != "unchanged" }.map { it.design }.distinct()
    return touched.map { design -> preview(design, c.before, c.after) }
  }

  private fun preview(design: String, before: CatalogBuilder.Output?, after: CatalogBuilder.Output?): DesignPreview {
    val now = after?.let { documents(it, design) }.orEmpty()
    val was = before?.let { documents(it, design) }.orEmpty()
    val chosen = pick(now.ifEmpty { was }) ?: return DesignPreview(design, "", 0, 0, emptyList(),
      if (size != null) "no template covers ${size.first}×${size.second} dp" else "no template is a Remote Compose document")
    val (w, h) = size ?: (chosen.target.size.minWidthDp?.takeIf { it > 0 } ?: DEFAULT_W) to (chosen.target.size.minHeightDp?.takeIf { it > 0 } ?: DEFAULT_H)
    fun same(list: List<TemplateEntry>) = list.firstOrNull { it.target == chosen.target } ?: list.firstOrNull { it.target.size.covers(w, h) && it.target.surface == chosen.target.surface }
    val a = same(now)?.let { it to after!! }
    val b = same(was)?.let { it to before!! }
    val contract = (a ?: b)!!.let { (t, output) -> contract(output, t) }
      ?: return DesignPreview(design, describe(chosen), w, h, emptyList(), "no contract ${chosen.contract}")
    val examples = contract.examples.entries.take(maxExamples)
    if (examples.isEmpty()) return DesignPreview(design, describe(chosen), w, h, emptyList(), "${contract.ref} has no examples")
    val spec = runCatching { RenderSpec(w, h, density) }.getOrElse { return DesignPreview(design, describe(chosen), w, h, emptyList(), "size out of range") }
    return try {
      val shots = examples.map { (name, data) ->
        val bindings = when (val r = ContractBinder.bind(contract, data)) {
          is BindResult.Ok -> r.bindings
          is BindResult.Invalid -> return DesignPreview(design, describe(chosen), w, h, emptyList(), "example '$name' doesn't bind: ${r.problems.map { it.name }}")
        }
        fun draw(pair: Pair<TemplateEntry, CatalogBuilder.Output>?, side: String): ByteArray? {
          val (t, output) = pair ?: return null
          val rc = output.files["blobs/${t.sha256}.rc"] ?: return null
          return render(rc, bindings, spec).also { File(out, file(design, name, side)).writeBytes(it) }
        }
        val after = draw(a, "after")
        // The same template on both sides draws the same: render it once.
        val unchangedTemplate = a != null && b != null && a.first.sha256 == b.first.sha256
        val before = if (unchangedTemplate) after else draw(b, "before")
        DesignPreview.Shot(
          example = name,
          before = before?.let { url(design, name, if (unchangedTemplate) "after" else "before") },
          after = after?.let { url(design, name, "after") },
          unchanged = before != null && after != null && before.contentEquals(after),
        )
      }
      DesignPreview(design, describe(chosen), w, h, shots)
    } catch (e: Throwable) {
      val link = generateSequence(e) { it.cause }.firstOrNull { it is UnsatisfiedLinkError }
      DesignPreview(design, describe(chosen), w, h, emptyList(),
        if (link != null) "Skia's native library didn't load on this host (${link.message})" else "${e::class.simpleName}: ${e.message}")
    }
  }

  /** [design]'s templates in [output] that decode: a placeholder or a damaged file can't be drawn. */
  private fun documents(output: CatalogBuilder.Output, design: String): List<TemplateEntry> =
    output.manifest.templates.filter { t ->
      t.designId == design && !t.revoked && output.files["blobs/${t.sha256}.rc"]?.let { TemplateInspector.facts(it) } != null
    }

  private fun pick(templates: List<TemplateEntry>): TemplateEntry? =
    if (size != null) templates.firstOrNull { it.target.size.covers(size.first, size.second) }
    else templates.maxWithOrNull(compareBy({ (it.target.size.minWidthDp ?: 0) * (it.target.size.minHeightDp ?: 0) }, { it.target.rcApiLevel }))

  private fun contract(output: CatalogBuilder.Output, t: TemplateEntry): Contract? =
    output.manifest.contracts.filter { it.ref == t.contract }.maxByOrNull { it.minor }

  private fun file(design: String, example: String, side: String) = "${safe(design)}-${safe(example)}-$side.png"

  private fun url(design: String, example: String, side: String) = imageBase + file(design, example, side)

  private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

  private fun describe(t: TemplateEntry): String = buildString {
    append(t.target.surface)
    t.target.container?.let { append('/').append(it) }
    val s = t.target.size
    if (listOf(s.minWidthDp, s.maxWidthDp, s.minHeightDp, s.maxHeightDp).any { it != null }) {
      append(" ${s.minWidthDp ?: 0}-${s.maxWidthDp ?: "max"}x${s.minHeightDp ?: 0}-${s.maxHeightDp ?: "max"}dp")
    }
  }

  private companion object {
    const val DEFAULT_W = 360
    const val DEFAULT_H = 200
  }
}
