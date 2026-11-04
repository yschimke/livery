package ee.schimke.livery.client

import ee.schimke.livery.protocol.ContractRef
import ee.schimke.livery.protocol.Design
import ee.schimke.livery.protocol.Manifest
import ee.schimke.livery.protocol.PlayerCapabilities
import ee.schimke.livery.protocol.TemplateEntry
import ee.schimke.livery.protocol.fits
import ee.schimke.livery.protocol.themeFits

/**
 * What a slot in the app asks for. [designId] is the chosen design (a skin, a decision's
 * template); without one, the contract's [variant] (then `default`, then any) is used.
 */
data class TemplateQuery(
  val contract: String,
  val caps: PlayerCapabilities,
  val widthDp: Int? = null,
  val heightDp: Int? = null,
  val designId: String? = null,
  val variant: String? = null,
  val theme: String? = null,
  val tags: Map<String, String> = emptyMap(),
)

/** Pure selection over a manifest: the same answer on every platform, and unit-testable. */
object TemplateSelector {
  /**
   * @param automatic which designs may be picked when [TemplateQuery.designId] doesn't name one
   *   (or names one that can't play): by default every design. The repository passes "shipped by
   *   default or installed", so a skin nobody installed is never drawn by surprise.
   */
  fun select(
    manifest: Manifest,
    query: TemplateQuery,
    supportedMajor: Int? = null,
    automatic: (Design) -> Boolean = { true },
  ): TemplateEntry? {
    val (_, major) = ContractRef.parse(query.contract)
    if (supportedMajor != null && supportedMajor != major) return null
    val liveDesigns = manifest.designs.filter { !it.revoked && it.contract == query.contract }.associateBy { it.id }

    val candidates = manifest.templates.filter { t ->
      !t.revoked &&
        t.contract == query.contract &&
        t.designId in liveDesigns &&
        t.bytes <= query.caps.maxBytes &&
        t.target.themeFits(query.caps, query.theme) &&
        t.target.fits(query.caps, query.widthDp, query.heightDp) &&
        query.tags.all { (k, v) -> t.tags[k] == v || liveDesigns.getValue(t.designId).tags[k] == v }
    }
    if (candidates.isEmpty()) return null

    fun best(of: List<TemplateEntry>): TemplateEntry? =
      of.sortedWith(
        compareByDescending<TemplateEntry> { it.target.rcApiLevel } // richest the player can draw
          .thenBy { if (query.theme != null && it.target.theme == query.theme) 0 else 1 }
          .thenBy { it.target.size.span() } // the tightest size fit
          .thenBy { it.sha256 }, // deterministic
      ).firstOrNull()

    query.designId?.let { id -> best(candidates.filter { it.designId == id })?.let { return it } }
    val auto = candidates.filter { automatic(liveDesigns.getValue(it.designId)) }
    val byVariant = { v: String -> auto.filter { liveDesigns.getValue(it.designId).variant == v } }
    query.variant?.let { v -> best(byVariant(v))?.let { return it } }
    best(byVariant(Design.DEFAULT_VARIANT))?.let { return it }
    return best(auto)
  }

  private fun ee.schimke.livery.protocol.SizeRange.span(): Long {
    fun w(a: Int?, b: Int?) = ((b ?: 100_000).toLong() - (a ?: 0).toLong())
    return w(minWidthDp, maxWidthDp) * w(minHeightDp, maxHeightDp)
  }
}
