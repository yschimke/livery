package ee.schimke.livery.review

import ee.schimke.composeai.rcplayer.compose.RcImageLoader
import ee.schimke.composeai.rcplayer.compose.RcTypefaceLoader
import ee.schimke.livery.client.BindResult
import ee.schimke.livery.client.Bindings
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
  private val render: (ByteArray, Bindings, RenderSpec, RcImageLoader, RcTypefaceLoader) -> ByteArray =
    { rc, b, spec, images, fonts -> TemplateRenderer.png(rc, b, spec, images, fonts) },
) {
  fun render(c: CompatibilityCheck.Comparison): List<DesignPreview> {
    out.mkdirs()
    val touched = c.report.templates.filter { it.status != "unchanged" }.map { it.design }.distinct()
    return (touched + drawnDifferently(c.before, c.after).filter { it !in touched }).map { design -> preview(design, c.before, c.after) }
  }

  /**
   * Designs whose documents are the same but would draw differently: a contract (its defaults and
   * examples) or a template's images or fonts changed. The report counts their templates unchanged.
   */
  private fun drawnDifferently(before: CatalogBuilder.Output?, after: CatalogBuilder.Output?): List<String> {
    if (before == null || after == null) return emptyList()
    val was = before.manifest.templates.associateBy { it.designId to it.target }
    return after.manifest.templates.filter { t ->
      val old = was[t.designId to t.target] ?: return@filter false
      contract(after, t) != contract(before, old) || !sameResources(t to after, old to before)
    }.map { it.designId }.distinct().sorted()
  }

  private fun preview(design: String, before: CatalogBuilder.Output?, after: CatalogBuilder.Output?): DesignPreview {
    val now = after?.let { documents(it, design) }.orEmpty()
    val was = before?.let { documents(it, design) }.orEmpty()
    // A size only the old tree covers (a variant this change removes) still draws its before.
    val chosen = pick(now) ?: pick(was) ?: return DesignPreview(design, "", 0, 0, emptyList(),
      if (size != null) "no template covers ${size.first}×${size.second} dp" else "no template is a Remote Compose document")
    // At its smallest size, or, with no minimum, the default within its maximum.
    val r = chosen.target.size
    val (w, h) = size ?: ((r.minWidthDp?.takeIf { it > 0 } ?: minOf(DEFAULT_W, r.maxWidthDp ?: DEFAULT_W)) to
      (r.minHeightDp?.takeIf { it > 0 } ?: minOf(DEFAULT_H, r.maxHeightDp ?: DEFAULT_H)))
    fun same(list: List<TemplateEntry>) = list.firstOrNull { it.target == chosen.target } ?: list.firstOrNull { it.target.size.covers(w, h) && it.target.surface == chosen.target.surface }
    val a = same(now)?.let { it to after!! }
    val b = same(was)?.let { it to before!! }
    val contract = (a ?: b)!!.let { (t, output) -> contract(output, t) }
      ?: return DesignPreview(design, describe(chosen), w, h, emptyList(), "no contract ${chosen.contract}")
    val examples = contract.examples.entries.take(maxExamples)
    if (examples.isEmpty()) return DesignPreview(design, describe(chosen), w, h, emptyList(), "${contract.ref} has no examples")
    val spec = runCatching { RenderSpec(w, h, density) }.getOrElse { return DesignPreview(design, describe(chosen), w, h, emptyList(), "size out of range") }
    return try {
      val beforeContract = b?.let { (t, output) -> contract(output, t) }
      val afterContract = a?.let { (t, output) -> contract(output, t) }
      val shots = examples.map { (name, data) ->
        // Each side binds the example with its own tree's contract: a changed default draws as it was.
        fun bindings(c: Contract?): Bindings? = when (val r = c?.let { ContractBinder.bind(it, it.examples[name] ?: data) }) {
          null -> null
          is BindResult.Ok -> r.bindings
          is BindResult.Invalid -> throw NotBound("example '$name' doesn't bind to ${c.ref}: ${r.problems.map { it.name }}")
        }
        fun draw(pair: Pair<TemplateEntry, CatalogBuilder.Output>?, c: Contract?, side: String): ByteArray? {
          val (t, output) = pair ?: return null
          val bound = bindings(c) ?: return null
          val rc = output.files["blobs/${t.sha256}.rc"] ?: return null
          return render(rc, bound, spec, CatalogImages(output, bound), CatalogFonts.forTemplate(output, t))
            .also { File(out, file(design, name, side)).writeBytes(it) }
        }
        val after = draw(a, afterContract, "after")
        // The same template, contract and assets on both sides draw the same: render it once.
        val same = a != null && b != null && a.first.sha256 == b.first.sha256 && beforeContract == afterContract &&
          sameResources(a, b)
        val before = if (same) after else draw(b, beforeContract, "before")
        DesignPreview.Shot(
          example = name,
          before = before?.let { url(design, name, if (same) "after" else "before") },
          after = after?.let { url(design, name, "after") },
          unchanged = before != null && after != null && before.contentEquals(after),
        )
      }
      DesignPreview(design, describe(chosen), w, h, shots)
    } catch (e: NotBound) {
      DesignPreview(design, describe(chosen), w, h, emptyList(), e.message)
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

  /**
   * Whether two templates draw with the same images and fonts: the same set of assets, each with
   * the same entry (a font's family, weight and style) and the same bytes.
   */
  private fun sameResources(a: Pair<TemplateEntry, CatalogBuilder.Output>, b: Pair<TemplateEntry, CatalogBuilder.Output>): Boolean {
    if (a.first.assets.toSet() != b.first.assets.toSet()) return false
    return a.first.assets.all { sha ->
      a.second.manifest.assets.firstOrNull { it.sha256 == sha } == b.second.manifest.assets.firstOrNull { it.sha256 == sha } &&
        asset(a.second, sha).contentEqualsOrNull(asset(b.second, sha))
    }
  }

  private fun ByteArray?.contentEqualsOrNull(other: ByteArray?): Boolean =
    if (this == null || other == null) this == other else contentEquals(other)

  private fun contract(output: CatalogBuilder.Output, t: TemplateEntry): Contract? =
    output.manifest.contracts.filter { it.ref == t.contract }.maxByOrNull { it.minor }

  /**
   * Readable, and unique per design and example: `-` can't separate two names that may hold it
   * (design `a-b` with example `c`, design `a` with example `b-c`), and [safe] folds characters
   * together, so a short hash of the exact pair follows them.
   */
  private fun file(design: String, example: String, side: String): String {
    val id = java.security.MessageDigest.getInstance("SHA-256").digest("$design\u0000$example".toByteArray())
      .take(4).joinToString("") { "%02x".format(it) }
    return "${safe(design)}.${safe(example)}.$id.$side.png"
  }

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

  private class NotBound(override val message: String) : Exception(message)

  private companion object {
    const val DEFAULT_W = 360
    const val DEFAULT_H = 200
  }
}
