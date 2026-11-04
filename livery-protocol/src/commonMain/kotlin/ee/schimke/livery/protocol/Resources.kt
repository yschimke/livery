package ee.schimke.livery.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A resource a template needs besides its document: an image or a font, content addressed under
 * [Manifest.assetBase] and verified like a blob. A client downloads a template's [TemplateEntry.assets]
 * with the template, so a widget or an offline screen never waits on a picture or a typeface.
 */
@Serializable
data class AssetEntry(
  val sha256: String,
  val bytes: Long,
  val mime: String,
  val kind: AssetKind,
  /** For an image: the name a document or dataset refers to (`hero`). */
  val name: String? = null,
  /** For a font: the face it provides. */
  val font: FontFace? = null,
  /** SPDX licence id; fonts must carry one (`OFL-1.1`, `Apache-2.0`). */
  val license: String? = null,
) {
  val fileName: String
    get() = "$sha256.${extension(mime)}"

  companion object {
    fun extension(mime: String): String = when (mime) {
      "font/ttf" -> "ttf"
      "font/otf" -> "otf"
      "font/woff2" -> "woff2"
      "image/svg+xml" -> "svg"
      else -> mime.substringAfter('/')
    }
  }
}

@Serializable
enum class AssetKind {
  @SerialName("image") Image,
  @SerialName("font") Font,
}

/** One face of a family: what a player's typeface loader is asked for. */
@Serializable
data class FontFace(
  val family: String,
  val weight: Int = 400,
  val italic: Boolean = false,
)

/**
 * How a client turns a remote image URL in a dataset (an ad's hero, a feed item's thumbnail) into
 * pixels without the document, or the device, talking to arbitrary hosts: every remote image is
 * fetched through this proxy, which resizes it for the slot, normalises the format, and strips
 * cookies and tracking. Without a proxy, a client fetches only from hosts it allowlists itself.
 *
 * @property urlTemplate `{url}` (percent-encoded source), `{width}`, `{height}` (pixels) and
 *   `{format}` are substituted, e.g. `https://img.example/{width}x{height}/{format}?u={url}`.
 * @property sourceHosts the source hosts this proxy will fetch; empty means any `https` host.
 */
@Serializable
data class ImageProxy(
  val urlTemplate: String,
  val sourceHosts: List<String> = emptyList(),
  val formats: List<String> = listOf("webp", "png"),
  val maxWidthPx: Int = 2048,
  val maxHeightPx: Int = 2048,
)

/** The forms an image value (`ValueType.Image`, `NamedValue.Image`) may take. */
sealed interface ImageRef {
  /** An image shipped in the catalog: `asset:<sha256>`, verified against [Manifest.assets]. */
  data class Asset(val sha256: String) : ImageRef

  /** A remote image, fetched through [ImageProxy] or from an allowlisted host: `https://…`. */
  data class Remote(val url: String) : ImageRef

  /** An image the app supplies itself (an entity picture, an avatar): `app:<key>`. */
  data class App(val key: String) : ImageRef

  companion object {
    private val SHA = Regex("[0-9a-f]{64}")

    /** Parses a dataset's image value; null for anything else (`http:`, `data:`, `file:`…). */
    fun parse(value: String): ImageRef? = when {
      value.startsWith("asset:") -> value.removePrefix("asset:").takeIf(SHA::matches)?.let(::Asset)
      value.startsWith("https://") && value.length > "https://".length -> Remote(value)
      value.startsWith("app:") && value.length > 4 -> App(value.removePrefix("app:"))
      else -> null
    }
  }
}
