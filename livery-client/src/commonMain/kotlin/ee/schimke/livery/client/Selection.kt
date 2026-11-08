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
 *
 * [minor] is the contract minor the app implements: templates that need a newer one (an input it
 * doesn't fill, an action it can't route) are skipped, so an older version of the same design plays
 * instead. Null accepts any minor, as clients did before minors were tracked.
 */
@kotlinx.serialization.Serializable
data class TemplateQuery(
  val contract: String,
  val caps: PlayerCapabilities,
  val widthDp: Int? = null,
  val heightDp: Int? = null,
  val designId: String? = null,
  val variant: String? = null,
  val theme: String? = null,
  val tags: Map<String, String> = emptyMap(),
  val minor: Int? = null,
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
    val candidates = manifest.templates.filter { eligible(manifest, it, query, liveDesigns) }
    if (candidates.isEmpty()) return null

    fun best(of: List<TemplateEntry>): TemplateEntry? =
      of.sortedWith(
        compareByDescending<TemplateEntry> { it.target.rcApiLevel } // richest the player can draw
          .thenBy { if (query.theme != null && it.target.theme == query.theme) 0 else 1 }
          .thenBy { it.target.size.span() } // the tightest size fit
          .thenBy { it.sha256 }, // deterministic
      ).firstOrNull()

    query.designId?.let { id -> best(candidates.filter { it.designId == id })?.let { return it } }
    // A design that isn't automatic plays only when named (above) or picked by an arm.
    val auto = candidates.filter { liveDesigns.getValue(it.designId).let { d -> d.automatic && automatic(d) } }
    val byVariant = { v: String -> auto.filter { liveDesigns.getValue(it.designId).variant == v } }
    query.variant?.let { v -> best(byVariant(v))?.let { return it } }
    best(byVariant(Design.DEFAULT_VARIANT))?.let { return it }
    return best(auto)
  }

  /**
   * The first of [queries] that has a template, with its index and that template: one call for a
   * slot that tries the most specific query first (tagged for this talk, then its track, then its
   * category) and falls back to the plain one. A design tagged for one item should have a variant
   * of its own, so that the plain query's `default` design still wins for every other item.
   */
  fun selectFirst(
    manifest: Manifest,
    queries: List<TemplateQuery>,
    supportedMajor: Int? = null,
    automatic: (Design) -> Boolean = { true },
  ): IndexedValue<TemplateEntry>? =
    queries.withIndex().firstNotNullOfOrNull { (i, q) -> select(manifest, q, supportedMajor, automatic)?.let { IndexedValue(i, it) } }

  /**
   * Whether [t] may play for [query]: right contract, a live design, not revoked, needing no newer
   * contract minor than the app's, within the player's byte budget, using no risky feature the
   * player doesn't allow, within its level, profiles, container, size and theme, and matching its tags. Exact
   * references (a decision's or feed item's `sha256`) pass through this same check.
   */
  fun eligible(
    manifest: Manifest,
    t: TemplateEntry,
    query: TemplateQuery,
    liveDesigns: Map<String, Design> = manifest.designs.filter { !it.revoked && it.contract == query.contract }.associateBy { it.id },
  ): Boolean {
    val design = liveDesigns[t.designId] ?: return false
    return !t.revoked &&
      t.contract == query.contract &&
      (query.minor == null || t.requiresMinor <= query.minor) &&
      t.bytes <= query.caps.maxBytes &&
      query.caps.features.containsAll(t.features) &&
      t.target.themeFits(query.caps, query.theme) &&
      t.target.fits(query.caps, query.widthDp, query.heightDp) &&
      query.tags.all { (k, v) -> t.tags[k] == v || design.tags[k] == v }
  }

  private fun ee.schimke.livery.protocol.SizeRange.span(): Long {
    fun w(a: Int?, b: Int?) = ((b ?: 100_000).toLong() - (a ?: 0).toLong())
    return w(minWidthDp, maxWidthDp) * w(minHeightDp, maxHeightDp)
  }
}
