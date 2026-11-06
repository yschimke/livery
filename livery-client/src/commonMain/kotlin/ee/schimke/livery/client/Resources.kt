package ee.schimke.livery.client

import ee.schimke.livery.protocol.AssetEntry
import ee.schimke.livery.protocol.AssetKind
import ee.schimke.livery.protocol.FontFace
import ee.schimke.livery.protocol.ImageProxy
import ee.schimke.livery.protocol.ImageRef

/**
 * Turns a dataset's image value into encoded image bytes for a player's image loader:
 * - `asset:<sha256>` from the catalog's verified assets;
 * - `https://…` through the catalog's [ImageProxy] (resized for the slot, one host to trust), or
 *   directly from a host in [directHosts] when the catalog has no proxy, and from nowhere else;
 * - `app:<key>` from the app's own [app] loader (an avatar, an entity picture).
 *
 * Remote results are cached in [cache] under the SHA-256 of the URL actually fetched, so the same
 * picture at the same size is fetched once. A document never fetches anything itself.
 */
class ImageResolver(
  private val repository: TemplateRepository,
  /** Must check redirects ([RedirectingFetcher]): a redirect is held to the same rule as the URL. */
  private val fetcher: RedirectingFetcher,
  private val crypto: Crypto,
  private val directHosts: Set<String> = emptySet(),
  private val app: (suspend (key: String, widthPx: Int, heightPx: Int) -> ByteArray?)? = null,
  private val cache: BlobStore = MemoryBlobStore(),
  private val maxBytes: Int = 4 * 1024 * 1024,
) {
  suspend fun resolve(value: String, widthPx: Int, heightPx: Int): ByteArray? =
    when (val ref = ImageRef.parse(value)) {
      null -> null
      is ImageRef.Asset -> repository.asset(ref.sha256)
      is ImageRef.App -> app?.invoke(ref.key, widthPx, heightPx)
      is ImageRef.Remote -> remote(ref.url, widthPx, heightPx)
    }

  /**
   * [resolve] without the network: a remote image only when it is already cached at this size.
   * For hosts that render on a deadline (widgets): on a miss, draw the fallback, [resolve] the
   * image in the background, and render again.
   */
  suspend fun cached(value: String, widthPx: Int, heightPx: Int): ByteArray? =
    when (val ref = ImageRef.parse(value)) {
      null -> null
      is ImageRef.Asset -> repository.asset(ref.sha256)
      is ImageRef.App -> app?.invoke(ref.key, widthPx, heightPx)
      is ImageRef.Remote -> remote(ref.url, widthPx, heightPx, network = false)
    }

  private suspend fun remote(url: String, widthPx: Int, heightPx: Int, network: Boolean = true): ByteArray? {
    val proxy = repository.manifest()?.imageProxy
    val target = remoteUrl(proxy, directHosts, url, widthPx, heightPx) ?: return null
    val key = crypto.sha256Hex(target.encodeToByteArray())
    cache.get(key)?.let { return it }
    if (!network) return null
    val r = fetcher.get(target, null, maxBytes.toLong()) { to -> redirectAllowed(proxy, directHosts, target, to) } as? FetchResult.Ok ?: return null
    cache.put(key, r.bytes)
    return r.bytes
  }

  companion object {
    /**
     * Whether a remote image fetch of [from] may follow a redirect to [to] (PROTOCOL.md §6.2): a
     * proxied fetch only within the proxy's own host, a direct fetch only to an allowlisted host.
     */
    fun redirectAllowed(proxy: ImageProxy?, directHosts: Set<String>, from: String, to: String): Boolean {
      val toHost = remoteHost(to) ?: return false
      if (proxy != null) return toHost == remoteHost(from)
      return directHosts.any { toHost == it || toHost.endsWith(".$it") }
    }

    /** The URL to fetch for [url] at a size, or null when no proxy or allowlist permits it. */
    fun remoteUrl(proxy: ImageProxy?, directHosts: Set<String>, url: String, widthPx: Int, heightPx: Int): String? {
      val host = remoteHost(url) ?: return null
      if (proxy != null) {
        if (proxy.sourceHosts.isNotEmpty() && proxy.sourceHosts.none { host == it || host.endsWith(".$it") }) return null
        return proxy.urlTemplate
          .replace("{url}", percentEncode(url))
          .replace("{width}", widthPx.coerceIn(1, proxy.maxWidthPx).toString())
          .replace("{height}", heightPx.coerceIn(1, proxy.maxHeightPx).toString())
          .replace("{format}", proxy.formats.firstOrNull() ?: "webp")
      }
      return url.takeIf { directHosts.any { host == it || host.endsWith(".$it") } }
    }

    /**
     * The host an `https` URL really reaches, or null when it can't be trusted to say: the authority
     * runs to the first `/`, `?`, `#` or `\`; one with user information (`a@b`, where the host is
     * `b`) or a backslash is refused outright, since no image needs either and parsers disagree on
     * them; an IPv6 literal keeps its brackets; the port is dropped.
     */
    fun remoteHost(url: String): String? {
      if (!url.startsWith("https://")) return null
      val rest = url.removePrefix("https://")
      val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' || it == '\\' }
      val authority = if (end < 0) rest else rest.substring(0, end)
      if ('@' in authority || (end >= 0 && rest[end] == '\\')) return null
      val host = if (authority.startsWith("[")) authority.substringBefore(']', "") .let { if (it.isEmpty()) return null else "$it]" }
        else authority.substringBefore(':')
      return host.lowercase().takeIf { it.isNotEmpty() }
    }

    /** RFC 3986 percent-encoding of everything but unreserved characters. */
    fun percentEncode(s: String): String = buildString {
      for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code) append(c.toChar())
        else { append('%'); append("0123456789ABCDEF"[c shr 4]); append("0123456789ABCDEF"[c and 15]) }
      }
    }
  }
}

/**
 * The font a player asks for, from the template's own font assets: the exact face if it has one,
 * else the nearest weight with the same style, else null (the player falls back to the system
 * font, or a downloadable-font resolver the app opted into for `google:` families).
 */
fun LoadedTemplate.fontAsset(face: FontFace): AssetEntry? {
  val family = assets.filter { it.kind == AssetKind.Font && it.font?.family.equals(face.family, ignoreCase = true) }
  return family.firstOrNull { it.font == face.copy(family = it.font!!.family) }
    ?: family.filter { it.font!!.italic == face.italic }.minByOrNull { kotlin.math.abs(it.font!!.weight - face.weight) }
}
