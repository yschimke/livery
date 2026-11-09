@file:OptIn(ExperimentalAtomicApi::class)

package ee.schimke.livery.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import ee.schimke.composeai.rcplayer.compose.RcImageLoader
import ee.schimke.composeai.rcplayer.compose.RcTypefaceLoader
import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.livery.client.Bindings
import ee.schimke.livery.client.ImageResolver
import ee.schimke.livery.client.LoadedTemplate
import ee.schimke.livery.client.PlanResult
import ee.schimke.livery.client.RenderPlan
import ee.schimke.livery.client.TemplateRepository
import ee.schimke.livery.protocol.AssetKind
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What [LiveryTemplate] keeps in memory between the cards it draws, so that only the first card of a
 * template does any work before it can draw, and a card the app [preload]ed does none.
 *
 * A feed draws many cards from few templates, and every card used to decode its template, read and
 * build its fonts, and fetch and decode its images again. Here each of those is done once per
 * content hash and shared:
 * - the **decoded document**, per template hash (each card still gets its own player state, seeded
 *   from its own values before its first frame, so no card shows or animates from another's data);
 * - **fonts**, per font asset hash, so a template whose faces another template already loaded draws
 *   its first frame with them;
 * - **decoded images**, per image reference and size, so an image already shown or preloaded draws
 *   on a card's first frame instead of popping in.
 *
 * A [LiveryHost] owns one. An app shares one host (and so one cache) across a screen's slots, and
 * calls [preload] when it knows what is coming: a feed page has arrived, a placement was decided.
 * Every cache is bounded and drops its oldest entries first; dropping one only costs the next card
 * the work again. Reads never block and never touch the disk or the network.
 */
class LiveryResources(
  private val repository: TemplateRepository,
  private val images: ImageResolver? = null,
  private val fallbackTypefaces: RcTypefaceLoader = RcTypefaceLoader.Default,
  private val fallbackImages: RcImageLoader = RcImageLoader.Default,
  maxTemplates: Int = 32,
  maxFonts: Int = 32,
  maxImages: Int = 64,
  maxPlans: Int = 128,
) {
  private val documents = BoundedCache<String, RcDocument>(maxTemplates)
  private val typefaces = BoundedCache<String, RcTypefaceLoader>(maxTemplates)
  private val fonts = BoundedCache<String, Font>(maxFonts)
  private val decoded = BoundedCache<String, ImageBitmap>(maxImages)
  private val plans = BoundedCache<SlotKey, Pair<Long, PlanResult>>(maxPlans)

  /**
   * The answer a [LiverySlot] last planned for [key] at catalog [revision], so a slot that comes
   * back (scrolled away and back, a screen reopened) draws it on its first frame instead of its
   * fallback while it plans again. An answer from another revision is never used: an install or a
   * revocation may have changed it.
   */
  fun lastPlan(key: Any?, revision: Long): PlanResult? =
    plans[SlotKey(key)]?.takeIf { it.first == revision }?.second

  /** Records [result] as [key]'s answer at [revision], for [lastPlan]. */
  fun keepPlan(key: Any?, revision: Long, result: PlanResult) = plans.put(SlotKey(key), revision to result)

  private data class SlotKey(val key: Any?)

  /** [template]'s decoded document: from memory, or decoded now (it never waits on I/O) and kept. */
  fun document(template: LoadedTemplate): RcDocument =
    documents[template.entry.sha256] ?: RcDocumentCodec.decode(template.bytes).also { documents.put(template.entry.sha256, it) }

  /**
   * [template]'s typefaces if they can be had without I/O: it ships no fonts, or every face it
   * ships is in memory (an earlier card, another template with the same face, or a [preload]).
   * Null means [typefaces] has to read them first.
   */
  fun typefacesNow(template: LoadedTemplate): RcTypefaceLoader? {
    typefaces[template.entry.sha256]?.let { return it }
    val faces = template.fontAssets()
    if (faces.any { fonts[it.sha256] == null }) return null
    return build(template, faces)
  }

  /** [template]'s typefaces, reading the faces not yet in memory from the repository. */
  suspend fun typefaces(template: LoadedTemplate): RcTypefaceLoader {
    typefacesNow(template)?.let { return it }
    val faces = template.fontAssets()
    for (face in faces) {
      if (fonts[face.sha256] != null) continue
      val bytes = repository.asset(face.sha256) ?: continue
      val font = runCatching { withContext(Dispatchers.Default) { fontFrom(face.sha256, bytes, face.weight, face.style) } }.getOrNull() ?: continue
      fonts.put(face.sha256, font)
    }
    return build(template, faces)
  }

  /** An image loader for one slot, whose `livery:value/` images come from [bindings]. */
  fun imageLoader(bindings: () -> Bindings): RcImageLoader =
    images?.let { LiveryImageLoader(it, bindings, fallbackImages, ::cachedImage, ::keepImage) } ?: fallbackImages

  /**
   * Gets everything [plan] needs before its first frame into memory: the document, its fonts, and
   * the images it draws (the template's own and the plan's image values). A slot that then draws
   * [plan] draws it complete on its first frame. Failures are left for the slot to meet as it would
   * have without a preload: a missing image is fetched again when the card draws.
   */
  suspend fun preload(plan: RenderPlan) {
    val document = withContext(Dispatchers.Default) { document(plan.template) }
    coroutineScope {
      launch { typefaces(plan.template) }
      val loader = imageLoader { plan.bindings }
      for (bitmap in document.operations.filterIsInstance<RcBitmapData>()) {
        if (bitmap.encoding != RcBitmapData.ENCODING_URL) continue
        if (loader.cached(bitmap) == null) launch { runCatching { loader.load(bitmap) } }
      }
    }
  }

  /**
   * Plans a [LiverySlot] ahead of time: runs [plan] (the same call the slot makes, for the same
   * [key]), keeps the answer for the slot ([lastPlan]) and [preload]s it. A slot that then composes
   * with [key] draws the finished template on its first frame, not its fallback. Call it for a feed
   * page's items when the page arrives, before they scroll into view.
   */
  suspend fun prepareSlot(key: Any?, plan: suspend () -> PlanResult): PlanResult {
    val revision = repository.revision.value
    val result = plan()
    keepPlan(key, revision, result)
    if (result is PlanResult.Ready) preload(result.plan)
    return result
  }

  /** [preload] for each of [plans] (a feed page), concurrently. */
  suspend fun preload(plans: Iterable<RenderPlan>) {
    coroutineScope { plans.forEach { launch { preload(it) } } }
  }

  private fun build(template: LoadedTemplate, faces: List<Face>): RcTypefaceLoader {
    val families = faces.groupBy { it.family }.mapValues { (_, f) -> f.mapNotNull { fonts[it.sha256] } }
      .filterValues { it.isNotEmpty() }
      .mapValues { (_, f) -> FontFamily(f) }
    val loader = if (families.isEmpty()) fallbackTypefaces else LiveryTypefaceLoader(families, fallbackTypefaces)
    // Only a complete set is kept: a face that failed to load is tried again by the next card.
    if (faces.all { fonts[it.sha256] != null }) typefaces.put(template.entry.sha256, loader)
    return loader
  }

  private fun cachedImage(key: String): ImageBitmap? = decoded[key]

  private fun keepImage(key: String, image: ImageBitmap) = decoded.put(key, image)

  private class Face(val sha256: String, val family: String, val weight: FontWeight, val style: FontStyle)

  private fun LoadedTemplate.fontAssets(): List<Face> = assets.mapNotNull { a ->
    val font = a.font ?: return@mapNotNull null
    if (a.kind != AssetKind.Font) return@mapNotNull null
    Face(a.sha256, font.family, FontWeight(font.weight), if (font.italic) FontStyle.Italic else FontStyle.Normal)
  }
}

/**
 * A small map that drops its oldest entry past [max]. Copy-on-write behind an atomic reference, so
 * composition reads it without a lock while a preload writes from another thread; entries are few
 * and written rarely, which is what makes copying cheap.
 */
internal class BoundedCache<K : Any, V : Any>(private val max: Int) {
  private val ref = AtomicReference<Map<K, V>>(emptyMap())

  operator fun get(key: K): V? = ref.load()[key]

  fun put(key: K, value: V) {
    while (true) {
      val current = ref.load()
      val next = LinkedHashMap<K, V>(current.size + 1)
      next.putAll(current)
      next.remove(key)
      next[key] = value
      while (next.size > max) next.remove(next.keys.first())
      if (ref.compareAndSet(current, next)) return
    }
  }

  val size: Int get() = ref.load().size
}
