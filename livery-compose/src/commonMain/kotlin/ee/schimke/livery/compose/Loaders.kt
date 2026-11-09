package ee.schimke.livery.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import ee.schimke.composeai.rcplayer.compose.RcFontVariations
import ee.schimke.composeai.rcplayer.compose.RcImageLoader
import ee.schimke.composeai.rcplayer.compose.RcTypefaceLoader
import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.livery.client.Bindings
import ee.schimke.livery.client.ImageResolver
import ee.schimke.livery.client.LoadedTemplate
import ee.schimke.livery.client.TemplateRepository
import ee.schimke.livery.protocol.AssetKind
import ee.schimke.livery.protocol.NamedValue

/**
 * Images a template draws by URL:
 * - `asset:<sha256>`, `https://…`, `app:<key>`: through [ImageResolver] (catalog assets, the image
 *   proxy or allowlist, the app's own loader);
 * - `livery:value/<input>`: the slot's current value for that image input, then as above.
 * Anything else, and every inline bitmap, is left to [fallback] (the player's default).
 */
class LiveryImageLoader(
  private val resolver: ImageResolver,
  private val bindings: () -> Bindings,
  private val fallback: RcImageLoader = RcImageLoader.Default,
  /** Decoded images already in memory, by [key] ([LiveryResources] shares them between slots). */
  private val memory: (key: String) -> ImageBitmap? = { null },
  /** Keeps an image this loader decoded, for [memory]. */
  private val keep: (key: String, image: ImageBitmap) -> Unit = { _, _ -> },
) : RcImageLoader {
  override fun cached(bitmap: RcBitmapData): ImageBitmap? = when (val ref = ref(bitmap)) {
    Ref.Fallback -> fallback.cached(bitmap)
    Ref.None -> null
    is Ref.Url -> if (ref.shareable) memory(key(ref.value, bitmap)) else null
  }

  override suspend fun load(bitmap: RcBitmapData): ImageBitmap? {
    val ref = when (val r = ref(bitmap)) {
      Ref.Fallback -> return fallback.load(bitmap)
      Ref.None -> return null
      is Ref.Url -> r.value
    }
    val key = key(ref, bitmap)
    if (ref.shareable()) memory(key)?.let { return it }
    val bytes = resolver.resolve(ref, bitmap.width.coerceAtLeast(1), bitmap.height.coerceAtLeast(1)) ?: return null
    return runCatching { decodeImage(bytes) }.getOrNull()?.also { if (ref.shareable()) keep(key, it) }
  }

  private sealed interface Ref {
    data object Fallback : Ref
    data object None : Ref
    class Url(val value: String) : Ref {
      val shareable: Boolean get() = value.shareable()
    }
  }



  private fun ref(bitmap: RcBitmapData): Ref {
    if (bitmap.encoding != RcBitmapData.ENCODING_URL) return Ref.Fallback
    val url = bitmap.data.decodeToString()
    val ref = if (url.startsWith(VALUE_PREFIX)) {
      (bindings().values[url.removePrefix(VALUE_PREFIX)] as? NamedValue.Image)?.ref ?: return Ref.None
    } else url
    if (!ref.startsWith("asset:") && !ref.startsWith("https://") && !ref.startsWith("app:")) return Ref.Fallback
    return Ref.Url(ref)
  }

  /** The same picture at the same requested size: what [ImageResolver] fetches once. */
  private fun key(ref: String, bitmap: RcBitmapData) = "${bitmap.width}x${bitmap.height} $ref"

  companion object {
    const val VALUE_PREFIX: String = "livery:value/"
  }
}

/**
 * Whether the picture behind [this] reference can never change, so a decoded copy may be shared:
 * a catalog asset is named by its hash, and a remote URL is fetched once per size anyway
 * ([ImageResolver]). An `app:` image is the app's own and may change under the same key (a new
 * avatar), so the app's loader is asked every time.
 */
private fun String.shareable(): Boolean = !startsWith("app:")

/**
 * Typefaces from the template's own font assets (verified, downloaded with it). Families the
 * template doesn't ship go to [fallback]: the system font, or a downloadable-font loader the app
 * opted into for `google:` families.
 */
class LiveryTypefaceLoader(
  private val faces: Map<String, FontFamily>,
  private val fallback: RcTypefaceLoader = RcTypefaceLoader.Default,
) : RcTypefaceLoader {
  override val families: Set<String> get() = faces.keys + fallback.families

  override fun typeface(family: String, variations: RcFontVariations?): FontFamily? =
    faces[family] ?: faces.entries.firstOrNull { it.key.equals(family, ignoreCase = true) }?.value ?: fallback.typeface(family, variations)

  companion object {
    /** Builds the families of [template]'s font assets, reading their bytes from [repository]. */
    suspend fun forTemplate(template: LoadedTemplate, repository: TemplateRepository, fallback: RcTypefaceLoader = RcTypefaceLoader.Default): LiveryTypefaceLoader {
      val byFamily = template.assets.filter { it.kind == AssetKind.Font && it.font != null }.groupBy { it.font!!.family }
      val families = byFamily.mapValues { (_, faces) ->
        FontFamily(faces.mapNotNull { a ->
          val bytes = repository.asset(a.sha256) ?: return@mapNotNull null
          fontFrom(a.sha256, bytes, FontWeight(a.font!!.weight), if (a.font!!.italic) FontStyle.Italic else FontStyle.Normal)
        })
      }.filterValues { it != FontFamily.Default }
      return LiveryTypefaceLoader(families, fallback)
    }
  }
}

/** Decodes PNG, WebP or JPEG bytes. */
internal expect fun decodeImage(bytes: ByteArray): ImageBitmap

/** A font from verified bytes, identified by their hash. */
internal expect fun fontFrom(sha256: String, bytes: ByteArray, weight: FontWeight, style: FontStyle): Font
