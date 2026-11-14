package ee.schimke.livery.client

import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import ee.schimke.livery.protocol.AssetEntry
import ee.schimke.livery.protocol.AssetKind
import ee.schimke.livery.protocol.FontFace
import ee.schimke.livery.protocol.ImageProxy
import ee.schimke.livery.protocol.ImageRef
import ee.schimke.livery.protocol.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class ResourcesTest {
  @Test fun imageRefsAcceptOnlyTheThreeForms() {
    assertEquals(ImageRef.Asset("a".repeat(64)), ImageRef.parse("asset:" + "a".repeat(64)))
    assertEquals(ImageRef.App("avatar"), ImageRef.parse("app:avatar"))
    assertTrue(ImageRef.parse("https://cdn.example/x.png") is ImageRef.Remote)
    listOf("http://cdn.example/x.png", "data:image/png;base64,AA", "file:///etc/passwd", "asset:short", "https://").forEach { assertNull(ImageRef.parse(it), it) }
  }

  @Test fun remoteImagesGoThroughTheProxyOrAnAllowlist() {
    val proxy = ImageProxy("https://img.example/{width}x{height}/{format}?u={url}", sourceHosts = listOf("cdn.example"), maxWidthPx = 1000)
    assertEquals("https://img.example/1000x200/webp?u=https%3A%2F%2Fa.cdn.example%2Fx.png%3Fq%3D1", ImageResolver.remoteUrl(proxy, emptySet(), "https://a.cdn.example/x.png?q=1", 4000, 200))
    assertNull(ImageResolver.remoteUrl(proxy, setOf("evil.example"), "https://evil.example/x.png", 10, 10), "proxy restricts its sources")
    assertEquals("https://ok.example/x.png", ImageResolver.remoteUrl(null, setOf("ok.example"), "https://ok.example/x.png", 10, 10))
    assertNull(ImageResolver.remoteUrl(null, setOf("ok.example"), "https://notok.example/x.png", 10, 10))
    assertNull(ImageResolver.remoteUrl(null, setOf("ok.example"), "https://ok.example.evil/x.png", 10, 10))
  }

  @Test fun templatesAreHandedOutOnlyWithTheirAssets() = runTest {
    val font = "font-bytes".encodeUtf8()
    val fontSha = FakeCrypto.sha(font)
    val base = Fx.manifest()
    val m = base.copy(
      assets = listOf(AssetEntry(fontSha, font.size.toLong(), "font/ttf", AssetKind.Font, font = FontFace("Figtree", 600), license = "OFL-1.1")),
      templates = base.templates.map { if (it.designId == "tile-default") it.copy(assets = listOf(fontSha), fonts = listOf("Figtree")) else it },
    )
    var fontUp = false
    val names = listOf("default-v7", "default-v8", "default-launcher")
    val host = Fetcher { url, _ ->
      when {
        url.endsWith("manifest.json") -> FetchResult.Ok(Json.encodeToString(Manifest.serializer(), m).encodeUtf8(), null)
        url.endsWith("$fontSha.ttf") -> if (fontUp) FetchResult.Ok(font, null) else FetchResult.Failed(503, "down")
        else -> names.map(Fx::blob).firstOrNull { FakeCrypto.sha(it) == url.substringAfterLast('/').removeSuffix(".rc") }?.let { FetchResult.Ok(it, null) } ?: FetchResult.Failed(404, "no")
      }
    }
    val repo = TemplateRepository(StaticSource("https://cdn.example/c"), host, FakeCrypto, TrustPolicy.AllowUnsigned, MemoryBlobStore(), MemoryKeyValueStore(), { 1_000 }, listOf(Fx.inApp.copy(maxRcApiLevel = 8)))
    val q = TemplateQuery("live/status-tile@1", Fx.inApp.copy(maxRcApiLevel = 8))
    repo.refresh()
    assertNull(repo.resolve(q), "no template without its font")
    fontUp = true
    repo.refresh(force = true)
    val t = repo.resolve(q)!!
    assertEquals(fontSha, t.fontAsset(FontFace("Figtree", 700))?.sha256, "nearest weight")
    assertNull(t.fontAsset(FontFace("Figtree", 400, italic = true)))
    assertEquals(font, repo.asset(fontSha))
  }
}

class RemoteHostTest {
  @Test fun theAllowlistChecksTheRealHost() {
    val allow = setOf("trusted.example")
    listOf(
      "https://trusted.example:443@attacker.example/image.png",
      "https://trusted.example@attacker.example/image.png",
      "https://attacker.example\\@trusted.example/image.png",
      "https://attacker.example#@trusted.example/image.png",
      "https://trusted.example.attacker.example/image.png",
    ).forEach { assertNull(ImageResolver.remoteUrl(null, allow, it, 10, 10), it) }
    assertEquals("https://TRUSTED.example:443/a.png", ImageResolver.remoteUrl(null, allow, "https://TRUSTED.example:443/a.png", 10, 10))
    assertEquals("[::1]", ImageResolver.remoteHost("https://[::1]:8443/a"))
  }
}

/** A widget renders from [ImageResolver.cached]; only [ImageResolver.resolve] goes to the network. */
class CachedImageTest {
  private class Images : RedirectingFetcher {
    val requests = mutableListOf<String>()
    override suspend fun get(url: String, etag: String?): FetchResult = get(url, etag) { false }
    override suspend fun get(url: String, etag: String?, maxBytes: Long, allowRedirect: (to: String) -> Boolean): FetchResult {
      requests += url
      return FetchResult.Ok("png:$url".encodeUtf8(), null)
    }
  }

  @Test fun cachedNeverFetchesAndSeesWhatResolveFetched() = kotlinx.coroutines.test.runTest {
    val net = Images()
    val repo = TemplateRepository(
      StaticSource("https://cdn.example/c"), { _, _ -> FetchResult.Failed(503, "off") }, FakeCrypto,
      TrustPolicy.AllowUnsigned, MemoryBlobStore(), MemoryKeyValueStore(), { 0L }, listOf(Fx.launcher),
    )
    val images = ImageResolver(repo, net, FakeCrypto, directHosts = setOf("cdn.example"))
    val url = "https://cdn.example/hero.png"
    assertNull(images.cached(url, 100, 50))
    assertEquals(emptyList(), net.requests, "a cache-only lookup never fetches")
    assertNotNull(images.resolve(url, 100, 50))
    assertNotNull(images.cached(url, 100, 50), "what resolve fetched is cached at that size")
    assertEquals(1, net.requests.size)
  }
}
