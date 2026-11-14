package ee.schimke.livery.review

import okio.ByteString
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import ee.schimke.composeai.rcplayer.compose.RcImageLoader
import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.livery.client.Bindings
import ee.schimke.livery.compose.LiveryImageLoader
import ee.schimke.livery.protocol.NamedValue
import ee.schimke.livery.publisher.CatalogBuilder
import org.jetbrains.skia.Image

/**
 * Images a template draws by URL, from the built [catalog] itself, as the app's
 * `LiveryImageLoader` resolves them from a downloaded one: `asset:<sha256>` from the catalog's own
 * assets, `livery:value/<input>` through the example's value for that input. The review has no
 * network, so `https://` and `app:` images draw the player's placeholder, the same on both sides.
 */
internal class CatalogImages(
  private val catalog: CatalogBuilder.Output,
  private val bindings: Bindings,
  private val fallback: RcImageLoader = RcImageLoader.Default,
) : RcImageLoader {
  override suspend fun load(bitmap: RcBitmapData): ImageBitmap? {
    if (bitmap.encoding != RcBitmapData.ENCODING_URL) return fallback.load(bitmap)
    val url = bitmap.data.decodeToString()
    val ref = if (url.startsWith(LiveryImageLoader.VALUE_PREFIX)) {
      (bindings.values[url.removePrefix(LiveryImageLoader.VALUE_PREFIX)] as? NamedValue.Image)?.ref ?: return null
    } else url
    val sha = ref.removePrefix("asset:").takeIf { it != ref } ?: return fallback.load(bitmap)
    val bytes = asset(catalog, sha) ?: return null
    return runCatching { Image.makeFromEncoded(bytes.toByteArray()).toComposeImageBitmap() }.getOrNull()
  }
}

/** An asset's bytes in a built catalog, by its hash (files are named `<sha256>.<ext>`). */
internal fun asset(catalog: CatalogBuilder.Output, sha256: String): ByteString? =
  catalog.files.entries.firstOrNull { (path, _) -> path.startsWith("assets/") && path.substringAfterLast('/').substringBefore('.') == sha256 }?.value
