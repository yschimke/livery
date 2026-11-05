package ee.schimke.livery.instantiate

import ee.schimke.composeai.rcplayer.edit.RcDisplay
import ee.schimke.composeai.rcplayer.edit.RcHardcodedValue
import ee.schimke.composeai.rcplayer.edit.bitmapUrls
import ee.schimke.composeai.rcplayer.edit.namedVariables
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.composeai.rcwidget.RcWearFrame
import ee.schimke.composeai.rcwidget.RcWearTemplates
import ee.schimke.composeai.rcwidget.RcWidgetDocuments
import ee.schimke.livery.client.Bindings
import ee.schimke.livery.client.ImageResolver
import ee.schimke.livery.protocol.NamedValue
import kotlinx.coroutines.CancellationException

sealed interface InstantiateResult {
  /**
   * @property actions for [TemplateInstantiator.instantiate] with `numericActions`, each numeric tap
   *   id with its contract action ([LauncherActions]); empty otherwise.
   * @property taps for [TemplateInstantiator.instantiateForWear], the tap each `PendingIntent` index
   *   stands for ([WearActions]); empty otherwise.
   */
  data class Ok(val bytes: ByteArray, val actions: Map<Int, String> = emptyMap(), val taps: List<WidgetTap> = emptyList()) : InstantiateResult
  /** The template can't be written for this host; draw the native fallback and report why. */
  data class Failed(val reason: String) : InstantiateResult
}

/**
 * Writes a slot's values into a values-free template (PROTOCOL.md §"Data", instantiate), for hosts
 * that play only the bytes they are handed: launcher widgets (`RemoteViews` draw instructions),
 * Glance Wear widgets, and server snapshots. The result shows exactly what a live player would
 * show for those values, and stays inside the template's own operations.
 *
 * - Every named value in [Bindings] becomes a constant in the document.
 * - Every image the document draws by URL is fetched and embedded, since such a host can load
 *   nothing: `livery:value/<input>` through the slot's own image value, `asset:`, `https:` and
 *   `app:` directly, all through [images] (an [ImageResolver], so the proxy, allowlist, redirect
 *   and size rules hold). An image that can't be had fails the whole instantiation, rather than
 *   draw a hole.
 * - State layouts that became constant are collapsed and the document is optimised, which keeps
 *   widget documents inside a launcher's small byte budget.
 *
 * The document work is rc-players' own ([RcWidgetDocuments.bake]); Livery supplies the values, the
 * images under its resource rules, and the budget.
 */
class TemplateInstantiator(
  private val images: suspend (ref: String, widthPx: Int, heightPx: Int) -> ByteArray?,
) {
  constructor(resolver: ImageResolver) : this({ ref, w, h -> resolver.resolve(ref, w, h) })

  /**
   * @param imageWidthPx / imageHeightPx the size to ask remote images at: the widget's size, as a
   *   document's bitmap URL carries none.
   * @param maxBytes the host's budget ([ee.schimke.livery.protocol.PlayerCapabilities.maxBytes]).
   * @param numericActions rewrite the template's named taps to numeric host actions, for a
   *   launcher widget ([LauncherActions]); the result's `actions` maps their ids back.
   */
  suspend fun instantiate(
    template: ByteArray,
    bindings: Bindings,
    imageWidthPx: Int,
    imageHeightPx: Int,
    maxBytes: Long = Long.MAX_VALUE,
    numericActions: Boolean = false,
  ): InstantiateResult = attempt {
    val baked = bake(template, bindings, imageWidthPx, imageHeightPx)
    // A launcher's RemoteViews player sends numeric host actions only.
    val rewritten = if (numericActions) LauncherActions.rewrite(baked) else LauncherActions.Rewritten(baked, emptyMap())
    budgeted(RcDocumentCodec.encode(rewritten.document), maxBytes) { bytes -> InstantiateResult.Ok(bytes, rewritten.actions) }
  }

  /**
   * [template] as the document for one Glance Wear widget: the slot's values and images written in
   * (images asked at the container's size), then rc-widget-core's Wear instantiation for [frame] on
   * a watch with [display] (the container's padding, corners and fill, density folded in, and the
   * Wear widget profile's rewrites), then the taps made `PendingIntent`s ([WearActions]): the
   * result's `taps[i]` is what `PendingIntent` index `i` must send.
   *
   * @param header the header Glance Wear writes for this widget's params, which the document takes.
   * @param containerArgb the container's fill (a state tint); null keeps the template's own.
   */
  suspend fun instantiateForWear(
    template: ByteArray,
    bindings: Bindings,
    frame: RcWearFrame,
    display: RcDisplay,
    header: RcHeader? = null,
    containerArgb: Int? = null,
    maxBytes: Long = Long.MAX_VALUE,
  ): InstantiateResult = attempt {
    val w = (frame.outerWidthDp * display.density).toInt()
    val h = (frame.outerHeightDp * display.density).toInt()
    val baked = bake(template, bindings, w, h)
    val adapted = RcWearTemplates.instantiate(baked, frame, display, containerArgb = containerArgb, header = header)
    if (!adapted.isPlayable) return@attempt InstantiateResult.Failed("not playable as a Wear widget: ${adapted.violations.take(3)}")
    val rewritten = WearActions.rewrite(adapted.document)
    budgeted(RcDocumentCodec.encode(rewritten.document), maxBytes) { bytes -> InstantiateResult.Ok(bytes, taps = rewritten.taps) }
  }

  private inline fun attempt(block: () -> InstantiateResult): InstantiateResult = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: ImageMissing) {
    InstantiateResult.Failed(e.message!!)
  } catch (e: Exception) {
    InstantiateResult.Failed("not instantiable: ${e.message ?: e::class.simpleName}")
  }

  private inline fun budgeted(bytes: ByteArray, maxBytes: Long, ok: (ByteArray) -> InstantiateResult): InstantiateResult =
    if (bytes.size > maxBytes) InstantiateResult.Failed("${bytes.size} bytes, budget $maxBytes") else ok(bytes)

  private suspend fun bake(template: ByteArray, bindings: Bindings, imageWidthPx: Int, imageHeightPx: Int): RcDocument {
    val document = RcDocumentCodec.decode(template, RcWireLimits())
    val fetched = LinkedHashMap<String, ByteArray>()
    for (url in document.bitmapUrls.distinct()) {
      val ref = imageRef(url, bindings) ?: throw ImageMissing("image $url has no value")
      fetched[url] = images(ref, imageWidthPx, imageHeightPx) ?: throw ImageMissing("image $ref unavailable")
    }
    // rc-widget-core's bake: embeds the images, writes the values in, collapses the state layouts
    // that became constant and optimises, exactly as rc-players' own widget hosts do.
    return RcWidgetDocuments.bake(document, hardcoded(bindings), fetched)
  }

  private class ImageMissing(message: String) : Exception(message)

  companion object {
    const val VALUE_PREFIX: String = "livery:value/"

    /** The named values a template reads (without the `USER:` domain), e.g. to check a contract. */
    fun reads(template: ByteArray): Set<String> =
      RcDocumentCodec.decode(template, RcWireLimits()).namedVariables.map { it.name.removePrefix("USER:") }.toSet()

    internal fun imageRef(url: String, bindings: Bindings): String? = when {
      url.startsWith(VALUE_PREFIX) -> (bindings.values[url.removePrefix(VALUE_PREFIX)] as? NamedValue.Image)?.ref
      url.startsWith("asset:") || url.startsWith("https://") || url.startsWith("app:") -> url
      else -> null
    }

    internal fun hardcoded(bindings: Bindings): Map<String, RcHardcodedValue> = buildMap {
      for ((name, v) in bindings.values) {
        when (v) {
          is NamedValue.Str -> put(name, RcHardcodedValue.Text(v.value))
          is NamedValue.I -> put(name, RcHardcodedValue.Integer(v.value))
          is NamedValue.L -> put(name, RcHardcodedValue.LongValue(v.value))
          is NamedValue.F -> put(name, RcHardcodedValue.FloatValue(v.value))
          is NamedValue.B -> put(name, RcHardcodedValue.BooleanValue(v.value))
          is NamedValue.Color -> put(name, RcHardcodedValue.Color(v.argb.toInt()))
          is NamedValue.Image -> Unit
        }
      }
    }
  }
}
