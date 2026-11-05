package ee.schimke.livery.client

import ee.schimke.livery.protocol.Manifest
import ee.schimke.livery.protocol.ManifestSignature
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class RepositoryTest {
  private val json = Json { encodeDefaults = false }
  private val key = "pk".encodeToByteArray()

  /** A fake host: the manifest, its signature and every blob in [Fx.manifest]. */
  private inner class Host(var manifest: Manifest, var signed: Boolean = true) : Fetcher {
    val requests = mutableListOf<String>()
    var down = false
    override suspend fun get(url: String, etag: String?): FetchResult {
      requests += url
      if (down) return FetchResult.Failed(503, "down")
      val bytes = json.encodeToString(Manifest.serializer(), manifest).encodeToByteArray()
      return when {
        url.endsWith("manifest.json") -> if (etag == "e${manifest.serial}") FetchResult.NotModified else FetchResult.Ok(bytes, "e${manifest.serial}")
        url.endsWith("manifest.sig.json") -> if (!signed) FetchResult.Failed(404, "none") else
          FetchResult.Ok(json.encodeToString(ManifestSignature.serializer(), ManifestSignature(keyId = "k", signature = Base64.encode(key + bytes.size.toString().encodeToByteArray()))).encodeToByteArray(), null)
        else -> {
          val sha = url.substringAfterLast('/').removeSuffix(".rc")
          val names = listOf("default-v7", "default-v8", "default-v9", "default-launcher", "default-wear-small", "retro-v7", "retro-launcher")
          names.map(Fx::blob).firstOrNull { FakeCrypto.sha(it) == sha }?.let { FetchResult.Ok(it, null) } ?: FetchResult.Failed(404, "no blob")
        }
      }
    }
  }

  private var now = 1_000L
  private val blobs = MemoryBlobStore()
  private val kv = MemoryKeyValueStore()

  private fun repo(host: Host, bundled: TemplateRepository.Bundled? = null) = TemplateRepository(
    StaticSource("https://cdn.example/catalog/main"), host, FakeCrypto,
    TrustPolicy.Signed(listOf(TrustedKey("k", key))), blobs, kv, { now }, listOf(Fx.inApp, Fx.launcher), bundled,
  )

  private val q = TemplateQuery("live/status-tile@1", Fx.inApp)

  @Test fun refreshDownloadsAlwaysDesignsForEveryTarget() = runTest {
    val host = Host(Fx.manifest())
    val r = repo(host)
    assertEquals(RefreshResult.Updated(1), r.refresh())
    // in-app v7/v8 (v9 too new) + launcher for the Always design; nothing of the OnRequest one.
    assertEquals(3, blobs.keys().size)
    assertEquals(LoadedTemplate.Origin.Fresh, r.resolve(q)?.origin)
    assertEquals(RefreshResult.Skipped("fresh"), r.refresh())
  }

  @Test fun unsignedAndRolledBackManifestsAreRefused() = runTest {
    val host = Host(Fx.manifest(serial = 5))
    val r = repo(host)
    r.refresh()
    host.manifest = Fx.manifest(serial = 4)
    assertIs<RefreshResult.Failed>(r.refresh(force = true)).also { assertTrue("rollback" in it.reason) }
    host.manifest = Fx.manifest(serial = 6); host.signed = false
    assertIs<RefreshResult.Failed>(r.refresh(force = true))
    assertEquals(5, r.manifest()?.serial)
  }

  @Test fun revalidatesWithEtag() = runTest {
    val host = Host(Fx.manifest())
    val r = repo(host)
    r.refresh(); now += 120_000
    assertEquals(RefreshResult.Unchanged, r.refresh())
  }

  @Test fun failingHostBacksOffAndKeepsServingCache() = runTest {
    val host = Host(Fx.manifest())
    val r = repo(host)
    r.refresh(); host.down = true; now += 120_000
    assertIs<RefreshResult.Failed>(r.refresh())
    assertEquals(RefreshResult.Skipped("backing off"), r.refresh())
    assertNotNull(r.resolve(q))
  }

  @Test fun installIsExplicitAndPreviewDoesNotInstall() = runTest {
    val r = repo(Host(Fx.manifest()))
    r.refresh()
    val retro = r.designs("live/status-tile@1").single { it.design.id == "tile-retro" }
    assertFalse(retro.installed); assertTrue(retro.downloadBytes > 0)
    // Chosen but not installed: draws the default, never nothing.
    assertEquals("tile-default", r.resolve(q.copy(designId = "tile-retro"))?.entry?.designId)
    assertEquals("tile-retro", r.preview("tile-retro", q)?.entry?.designId)
    assertFalse("tile-retro" in r.installedIds())
    assertTrue(r.install("tile-retro"))
    assertTrue(r.designs("live/status-tile@1").single { it.design.id == "tile-retro" }.installed)
    r.uninstall("tile-retro"); r.prune()
    assertNull(blobs.get(Fx.manifest().templates[5].sha256))
  }

  @Test fun revisionMovesWhenWhatResolveCanAnswerChanges() = runTest {
    val host = Host(Fx.manifest())
    val r = repo(host)
    assertEquals(0, r.revision.value)
    r.refresh()
    assertEquals(1, r.revision.value, "a newly accepted catalog")
    r.refresh(force = true)
    assertEquals(1, r.revision.value, "not modified: nothing new to answer")
    assertTrue(r.install("tile-retro"))
    assertEquals(2, r.revision.value)
    r.uninstall("tile-retro")
    assertEquals(3, r.revision.value)
  }

  @Test fun fallsBackToBundledWhenNothingIsCached() = runTest {
    val bundledManifest = Fx.manifest()
    val r = repo(Host(Fx.manifest()).apply { down = true }, TemplateRepository.Bundled(bundledManifest) { sha ->
      listOf("default-v8").map(Fx::blob).firstOrNull { FakeCrypto.sha(it) == sha }
    })
    r.refresh()
    assertEquals(LoadedTemplate.Origin.Bundled, r.resolve(q)?.origin)
  }
}
