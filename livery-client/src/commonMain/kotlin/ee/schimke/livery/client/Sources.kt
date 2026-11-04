package ee.schimke.livery.client

/**
 * Where a catalog channel's manifest lives. Everything else (blobs, previews) is resolved from the
 * manifest's own `blobBase` / `previewBase`, relative to [manifestUrl], so a source is one URL.
 */
interface CatalogSource {
  /** Cache namespace: one per catalog + channel + host, so switching channel is clean. */
  val id: String
  val manifestUrl: String
  val signatureUrl: String get() = resolveUrl(manifestUrl, "manifest.sig.json")
}

/** Any static host: Pages, S3, a CDN, a design server's `/v1/catalogs/<id>/<channel>/`. */
data class StaticSource(val baseUrl: String, override val id: String = baseUrl) : CatalogSource {
  override val manifestUrl: String = baseUrl.trimEnd('/') + "/manifest.json"
}

/**
 * A GitHub release as a catalog. Release assets have flat names, so the publisher writes
 * `blobBase = "https://github.com/<owner>/<repo>/releases/download/<tag>/"` into the manifest and
 * uploads `<sha>.rc` assets beside `manifest.json`. [tag] `latest` follows the newest release.
 *
 * GitHub download URLs don't send CORS headers: browsers need a mirror (Pages) or a proxy.
 */
data class GitHubReleaseSource(
  val owner: String,
  val repo: String,
  val tag: String = "latest",
  override val id: String = "github:$owner/$repo@$tag",
) : CatalogSource {
  override val manifestUrl: String =
    if (tag == "latest") "https://github.com/$owner/$repo/releases/latest/download/manifest.json"
    else "https://github.com/$owner/$repo/releases/download/$tag/manifest.json"
}

/** Templates shipped inside the app (assets, resources, a web bundle): the last fallback. */
data class BundledSource(val assetRoot: String, override val id: String = "bundled:$assetRoot") : CatalogSource {
  override val manifestUrl: String = "asset://" + assetRoot.trim('/') + "/manifest.json"
}

/** RFC 3986-ish resolution, enough for manifest-relative paths. */
fun resolveUrl(base: String, ref: String): String {
  if (ref.contains("://")) return ref
  val dir = base.substringBeforeLast('/', "") + "/"
  if (ref.startsWith("/")) {
    val scheme = base.substringBefore("://")
    val host = base.substringAfter("://").substringBefore('/')
    return "$scheme://$host$ref"
  }
  return dir + ref
}
