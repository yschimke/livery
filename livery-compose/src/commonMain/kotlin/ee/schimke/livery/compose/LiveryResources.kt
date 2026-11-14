@file:OptIn(ExperimentalAtomicApi::class)

package ee.schimke.livery.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import ee.schimke.composeai.rcplayer.compose.RcImageLoader
import ee.schimke.composeai.rcplayer.compose.RcDisplay
import ee.schimke.composeai.rcplayer.compose.RcPreparation
import ee.schimke.composeai.rcplayer.compose.RcPreprocessor
import ee.schimke.composeai.rcplayer.edit.forDisplay
import ee.schimke.composeai.rcplayer.compose.RcPreparedDocument
import ee.schimke.composeai.rcplayer.compose.RcPreparedDocumentCache
import ee.schimke.composeai.rcplayer.compose.RcTypefaceLoader
import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.livery.client.Bindings
import ee.schimke.livery.client.ImageResolver
import ee.schimke.livery.client.LiveryTrace
import ee.schimke.livery.client.LoadedTemplate
import ee.schimke.livery.client.PlanResult
import ee.schimke.livery.client.RenderPlan
import ee.schimke.livery.client.TemplateQuery
import ee.schimke.livery.client.TemplateRepository
import ee.schimke.livery.protocol.AssetKind
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
 * - the **prepared document** (decoded, linked, laid out, its player-state seed built), per template
 *   content, in the player's own [RcPreparedDocumentCache] ([documents]), which [LiveryHost]
 *   hands the player as its preparation strategy (each card still gets its own player state,
 *   seeded from its own values before its first frame, so no card shows or animates from
 *   another's data);
 * - **fonts**, per font asset hash, so a template whose faces another template already loaded draws
 *   its first frame with them;
 * - **decoded images**, per image reference and size, so an image already shown or preloaded draws
 *   on a card's first frame instead of popping in.
 *
 * A [LiveryHost] owns one. An app shares one host (and so one cache) across a screen's slots, and
 * calls [preload] when it knows what is coming: a feed page has arrived, a placement was decided.
 * Every cache is bounded and drops its oldest entries first; dropping one only costs the next card
 * the work again. Reads never block and never touch the disk or the network, and preparing a
 * document ([prepare], [preload], [warm]) runs on [Dispatchers.Default], never on the caller's
 * thread.
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
  /**
   * Rewrites each template for the display it is drawn on before it is prepared, in the background
   * and once per template and display: by default [ForDisplay] (density hardcoded, values no card
   * binds frozen, constants folded). Null prepares templates as they were published.
   */
  preprocessor: RcPreprocessor? = ForDisplay,
) {
  /**
   * The prepared documents, by content: the cache the player's [RcPreparation.Background] reads
   * during composition, so composition only ever looks a template up here.
   */
  val documents: RcPreparedDocumentCache = RcPreparedDocumentCache(maxTemplates, preprocessor)

  /**
   * The display this host's slots are composed on, as the last [LiverySlot] or [LiveryTemplate] to
   * compose read it (`LocalDensity`): what the calls below prepare for when they are not handed one,
   * so a template warmed ahead of time is the one composition looks up. Before anything has
   * composed it is the device's display where the platform knows it (Android's system density and
   * font scale, which an app's first composition reads unless it overrides them), so a splash
   * screen's `startup` prepares what the first slot looks up; elsewhere null until something has
   * composed, and a template that is then drawn for another display plays as published while its
   * rewrite is prepared ([LiveryTemplate]).
   */
  var display: RcDisplay? = platformDisplay()
  private val typefaces = BoundedCache<String, RcTypefaceLoader>(maxTemplates)
  private val fonts = BoundedCache<String, Font>(maxFonts)
  private val decoded = BoundedCache<String, ImageBitmap>(maxImages)
  private val plans = BoundedCache<SlotKey, Pair<Long, PlanResult>>(maxPlans)
  private val heights = BoundedCache<String, Int>(maxTemplates * 4)
  private val unplayable = BoundedCache<String, Unit>(maxTemplates)
  private val missCount = AtomicInt(0)

  /**
   * How many times a card composed a template that was not prepared yet, so it held its place
   * instead of drawing on its first frame. Zero for a feed whose templates were all warmed.
   */
  val misses: Int
    get() = missCount.load()

  internal fun recordMiss() {
    missCount.addAndFetch(1)
  }

  /**
   * Whether [template] failed to prepare: its bytes are not a document this player can play (a
   * catalog's placeholder, a newer wire format). Its cards draw nothing rather than fail the app.
   */
  fun cannotPlay(template: LoadedTemplate): Boolean = unplayable[template.entry.sha256] != null

  /**
   * The height [template] last drew at for a width of [widthPx], or null: what a card of it reserves
   * while it cannot draw yet, so the list around it does not move when it does. Another card's data
   * may wrap differently, so it is the template's usual height, not this card's.
   */
  fun lastHeight(template: LoadedTemplate, widthPx: Int): Int? = heights[heightKey(template, widthPx)]

  /** Records the height [template] drew at for [widthPx], for [lastHeight]. */
  fun keepHeight(template: LoadedTemplate, widthPx: Int, heightPx: Int) {
    if (heightPx > 0) heights.put(heightKey(template, widthPx), heightPx)
  }

  private fun heightKey(template: LoadedTemplate, widthPx: Int) = "${template.entry.sha256}@$widthPx"

  /**
   * The answer the [LiverySlot] named [slotId] last planned for [key] at catalog [revision], so a
   * slot that comes back (scrolled away and back, a screen reopened) draws it on its first frame
   * instead of its fallback while it plans again. [slotId] names the slot on this host (`home.top`,
   * `home-feed`): [key] alone only says when one slot replans, and two slots may share a key. An
   * answer from another revision is never used: an install or a revocation may have changed it.
   */
  fun lastPlan(slotId: String, key: Any?, revision: Long): PlanResult? =
    plans[SlotKey(slotId, key)]?.takeIf { it.first == revision }?.second

  /** Records [result] as the answer of slot [slotId] for [key] at [revision], for [lastPlan]. */
  fun keepPlan(slotId: String, key: Any?, revision: Long, result: PlanResult) =
    plans.put(SlotKey(slotId, key), revision to result)

  private data class SlotKey(val slotId: String, val key: Any?)

  /**
   * [template]'s prepared document: decoded, linked and laid out once, then shared by every card of
   * the template (each card still gets its own player state). From memory, or prepared now, on the
   * calling thread (it never waits on I/O), and kept. Composition uses [preparedNow] instead.
   */
  fun prepared(template: LoadedTemplate, display: RcDisplay? = this.display): RcPreparedDocument =
    documents.prepareNow(template.bytes, display)

  /** [prepared], or null for a template that cannot be played, which is remembered ([cannotPlay]). */
  fun preparedOrNull(template: LoadedTemplate, display: RcDisplay? = this.display): RcPreparedDocument? =
    try {
      prepared(template, display)
    } catch (_: Exception) {
      unplayable.put(template.entry.sha256, Unit)
      null
    }

  /** [template]'s prepared document for [display] if it is in memory; never prepares. */
  fun preparedNow(template: LoadedTemplate, display: RcDisplay? = this.display): RcPreparedDocument? =
    documents.peek(template.bytes, display)

  /**
   * [template]'s prepared document for [display], rewritten for it and prepared on [context]
   * unless it is in memory.
   */
  suspend fun prepare(
    template: LoadedTemplate,
    display: RcDisplay? = this.display,
    context: CoroutineContext = Dispatchers.Default,
  ): RcPreparedDocument =
    // The document is decoded, linked and rewritten for the display by rc-players on [context]: the whole of
    // it is this section, whichever thread it runs on (the player's own spans, when bridged, nest inside it).
    LiveryTrace.async({ "livery:prepare:${template.entry.designId}" }) { documents.prepare(template.bytes, display, context) }

  /**
   * [prepare], or null for a template that cannot be played, which is remembered ([cannotPlay])
   * rather than thrown: warming a catalog, or a card, must not fail for one bad template.
   */
  suspend fun prepareOrNull(
    template: LoadedTemplate,
    display: RcDisplay? = this.display,
    context: CoroutineContext = Dispatchers.Default,
  ): RcPreparedDocument? =
    try {
      prepare(template, display, context)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      unplayable.put(template.entry.sha256, Unit)
      null
    }

  /**
   * Whether a screen these resources serve has drawn its first frame. Until then a slot or template
   * that is not ready may wait for its work, on the main thread, up to the host's
   * [LiveryHost.firstFrameBudgetMillis] in all; from then on nothing waits ([LiveryTemplate]).
   */
  @kotlin.concurrent.Volatile internal var firstFrameDrawn: Boolean = false

  /**
   * Whether something else covers the first frame: a [startup] holding the splash screen, which
   * keeps the starting window up while the work runs, so waiting on the main thread buys nothing.
   */
  @kotlin.concurrent.Volatile internal var firstFrameCovered: Boolean = false

  /** When the first wait for a first frame started: the budget is shared from there. */
  private var firstFrameWaitStart: kotlin.time.TimeSource.Monotonic.ValueTimeMark? = null

  /** Where work a first frame waits on runs: off the main thread, which only waits for it. */
  internal val firstFrameScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** How long a first frame may still wait, of [budgetMillis]: 0 once it has drawn. */
  internal fun firstFrameWaitLeft(budgetMillis: Long): Long {
    if (firstFrameDrawn || firstFrameCovered || budgetMillis <= 0) return 0
    val start = firstFrameWaitStart ?: kotlin.time.TimeSource.Monotonic.markNow().also { firstFrameWaitStart = it }
    return (budgetMillis - start.elapsedNow().inWholeMilliseconds).coerceAtLeast(0)
  }

  /**
   * Starts [work] off the main thread and waits for it here, up to what is left of [budgetMillis]
   * before the first frame: its value, or null when there is no time left or it did not finish.
   * The work carries on either way, for whatever asks for it next.
   */
  internal fun <T> beforeFirstFrame(budgetMillis: Long, work: suspend LiveryResources.() -> T): Deferred<T>? {
    if (firstFrameWaitLeft(budgetMillis) <= 0) return null
    return firstFrameScope.async { work() }
  }

  /** [deferred]'s value if it arrives within what is left of [budgetMillis] before the first frame. */
  internal fun <T> awaitBeforeFirstFrame(budgetMillis: Long, deferred: Deferred<T>): T? {
    val left = firstFrameWaitLeft(budgetMillis)
    return if (left > 0) awaitBlocking(deferred, left) else null
  }

  /**
   * Gets what [template]'s cards need before they can draw into memory: its prepared document and
   * its fonts (not the images a card shows, which arrive with the card). A card of it then draws
   * on its first frame. What [LiverySlot] waits for before it swaps a new answer in.
   */
  suspend fun readyToDraw(
    template: LoadedTemplate,
    display: RcDisplay? = this.display,
    context: CoroutineContext = Dispatchers.Default,
  ) {
    LiveryTrace.async({ "livery:ready:${template.entry.designId}" }) {
      coroutineScope {
        launch { prepareOrNull(template, display, context) }
        launch { runCatching { typefaces(template) } }
      }
    }
  }

  /**
   * Prepares, in the background, the templates [queries] choose today (each query's first match):
   * call it when a screen of them is coming (a catalog loaded, a list's data arrived), so the
   * first card of each draws on its first frame too. Queries with no template are skipped.
   */
  suspend fun warm(queries: Iterable<TemplateQuery>, display: RcDisplay? = this.display) {
    coroutineScope {
      for (query in queries) launch { repository.resolve(query)?.let { readyToDraw(it, display) } }
    }
  }

  /** [template]'s decoded document, from [prepared]. */
  fun document(template: LoadedTemplate): RcDocument = prepared(template).document

  /**
   * [template]'s typefaces if they can be had without I/O: it ships no fonts, or every face it
   * ships is in memory (an earlier card, another template with the same face, or a [preload]).
   * Null means [typefaces] has to read them first.
   */
  fun typefacesNow(template: LoadedTemplate): RcTypefaceLoader? {
    typefaces[template.typefaceKey()]?.let { return it }
    val faces = template.fontAssets()
    // Read once into a local map: the shared cache may evict between a check and a later read.
    val loaded = faces.associate { face -> face.id to (fonts[face.id] ?: return null) }
    return build(template, faces, loaded)
  }

  /** [template]'s typefaces, reading the faces not yet in memory from the repository. */
  suspend fun typefaces(template: LoadedTemplate): RcTypefaceLoader {
    typefacesNow(template)?.let { return it }
    return LiveryTrace.async({ "livery:fonts:${template.entry.designId}" }) { readTypefaces(template) }
  }

  private suspend fun readTypefaces(template: LoadedTemplate): RcTypefaceLoader {
    val faces = template.fontAssets()
    // The faces this call holds, whatever the bounded shared cache evicts meanwhile (a template
    // with more faces than it keeps, or a concurrent preload): the cache is only for reuse.
    val loaded = mutableMapOf<String, Font>()
    for (face in faces) {
      fonts[face.id]?.let { loaded[face.id] = it; continue }
      val bytes = repository.asset(face.sha256) ?: continue
      val font = runCatching {
        withContext(Dispatchers.Default) { LiveryTrace.section({ "livery:font-load:${face.sha256.take(8)}" }) { fontFrom(face.sha256, bytes, face.weight, face.style) } }
      }.getOrNull() ?: continue
      fonts.put(face.id, font)
      loaded[face.id] = font
    }
    return build(template, faces, loaded)
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
  suspend fun preload(plan: RenderPlan, display: RcDisplay? = this.display) {
    val document = prepareOrNull(plan.template, display)?.document ?: return
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
   * [slotId] and [key]), keeps the answer for the slot ([lastPlan]) and [preload]s it. A slot that
   * then composes with that `slotId` and [key] draws the finished template on its first frame, not
   * its fallback. Call it for a feed page's items when the page arrives, before they scroll into
   * view.
   */
  suspend fun prepareSlot(slotId: String, key: Any?, plan: suspend () -> PlanResult): PlanResult {
    val revision = repository.revision.value
    val result = plan()
    keepPlan(slotId, key, revision, result)
    if (result is PlanResult.Ready) preload(result.plan)
    return result
  }

  /** [preload] for each of [plans] (a feed page), concurrently. */
  suspend fun preload(plans: Iterable<RenderPlan>) {
    coroutineScope { plans.forEach { launch { preload(it) } } }
  }

  private fun build(template: LoadedTemplate, faces: List<Face>, loaded: Map<String, Font>): RcTypefaceLoader {
    val families = faces.groupBy { it.family }.mapValues { (_, f) -> f.mapNotNull { loaded[it.id] } }
      .filterValues { it.isNotEmpty() }
      .mapValues { (_, f) -> FontFamily(f) }
    val loader = if (families.isEmpty()) fallbackTypefaces else LiveryTypefaceLoader(families, fallbackTypefaces)
    // Only a complete set is kept: a face that failed to load is tried again by the next card.
    if (faces.all { it.id in loaded }) typefaces.put(template.typefaceKey(), loader)
    return loader
  }

  private fun cachedImage(key: String): ImageBitmap? = decoded[key]

  private fun keepImage(key: String, image: ImageBitmap) = decoded.put(key, image)

  companion object {
    /**
     * `rc-player-edit`'s `forDisplay`: the template's density and font scale hardcoded, every named
     * value outside its `USER:` inputs frozen, and the constants that leaves folded, so every card
     * evaluates fewer expressions.
     */
    val ForDisplay: RcPreprocessor = RcPreprocessor { document, display ->
      // On the preparation thread. Livery's `prepare` section minus this one is rc-players' decode and link.
      LiveryTrace.section({ "livery:for-display" }) { document.forDisplay(display.density, display.fontScale) }
    }
  }

  /**
   * A loader is for one document with one set of faces: a refreshed catalog may give the same
   * document other font assets, which must not draw with the old ones.
   */
  private fun LoadedTemplate.typefaceKey(): String =
    fontAssets().joinToString(",", prefix = "${entry.sha256}:") { "${it.sha256}/${it.family}/${it.weight.weight}/${it.style}" }

  private class Face(val sha256: String, val family: String, val weight: FontWeight, val style: FontStyle) {
    /** A `Font` carries its weight and style, so the same bytes declared differently are another font. */
    val id: String get() = "$sha256/${weight.weight}/$style"
  }

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
