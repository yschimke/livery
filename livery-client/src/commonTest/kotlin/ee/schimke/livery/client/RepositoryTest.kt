package ee.schimke.livery.client

import ee.schimke.livery.protocol.Manifest
import ee.schimke.livery.protocol.NamedValue
import ee.schimke.livery.protocol.PlacementRule
import ee.schimke.livery.protocol.ThemePack
import ee.schimke.livery.protocol.ManifestSignature
import ee.schimke.livery.protocol.TemplateRef
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class RepositoryTest {
  private val json = Json { encodeDefaults = false }
  private val key = "pk".encodeToByteArray()

  /** A fake host: the manifest, its signature and every blob in [Fx.manifest]. */
  private inner class Host(var manifest: Manifest, var signed: Boolean = true) : Fetcher {
    val requests = mutableListOf<String>()
    var down = false
    var blobsDown = false
    var blobRequested: CompletableDeferred<Unit>? = null
    var resumeBlobs: CompletableDeferred<Unit>? = null
    var pauseSha: String? = null
    override suspend fun get(url: String, etag: String?): FetchResult {
      requests += url
      if (down) return FetchResult.Failed(503, "down")
      if (blobsDown && !url.endsWith(".json")) return FetchResult.Failed(503, "blobs down")
      val bytes = json.encodeToString(Manifest.serializer(), manifest).encodeToByteArray()
      return when {
        url.endsWith("manifest.json") -> if (etag == "e${manifest.serial}") FetchResult.NotModified else FetchResult.Ok(bytes, "e${manifest.serial}")
        url.endsWith("manifest.sig.json") -> if (!signed) FetchResult.Failed(404, "none") else
          FetchResult.Ok(json.encodeToString(ManifestSignature.serializer(), ManifestSignature(keyId = "k", signature = Base64.encode(key + bytes.size.toString().encodeToByteArray()))).encodeToByteArray(), null)
        else -> {
          val sha = url.substringAfterLast('/').removeSuffix(".rc")
          if (pauseSha == null || pauseSha == sha) {
            blobRequested?.complete(Unit)
            resumeBlobs?.await()
          }
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

  private fun replacement(): Manifest {
    return Fx.manifest(serial = 2).let { m -> m.copy(templates = m.templates.map { entry ->
      val bytes = Fx.blob(if (entry.target.surface == "launcher-widget") "retro-launcher" else "retro-v7")
      if (entry.designId == "tile-default") entry.copy(sha256 = FakeCrypto.sha(bytes), bytes = bytes.size.toLong()) else entry
    }) }
  }

  @Test fun refreshKeepsThePlayableSnapshotUntilReplacementDownloadsFinish() = runTest {
    val host = Host(Fx.manifest())
    val r = repo(host)
    r.refresh()
    val original = assertNotNull(r.resolve(q))
    host.manifest = replacement()
    host.blobRequested = CompletableDeferred()
    host.resumeBlobs = CompletableDeferred()
    host.pauseSha = FakeCrypto.sha(Fx.blob("retro-launcher"))
    val refreshing = async { r.refresh(force = true) }
    host.blobRequested!!.await()
    assertEquals(1, r.current()?.serial)
    assertEquals(original.entry.sha256, r.resolve(q)?.entry?.sha256)
    assertEquals(1, repo(host).current()?.serial, "a concurrently opened repository sees the playable disk snapshot")
    val downloaded = FakeCrypto.sha(Fx.blob("retro-v7"))
    assertNotNull(blobs.get(downloaded))
    r.prune()
    assertNotNull(blobs.get(downloaded), "pruning must retain the replacement while its other downloads are pending")
    host.resumeBlobs!!.complete(Unit)
    assertEquals(RefreshResult.Updated(2), refreshing.await())
    assertEquals(2, r.current()?.serial)
    assertEquals(FakeCrypto.sha(Fx.blob("retro-v7")), r.resolve(q)?.entry?.sha256)
  }

  @Test fun failedReplacementKeepsCacheButStillRecordsTheAcceptedSerial() = runTest {
    val host = Host(Fx.manifest())
    val r = repo(host)
    r.refresh()
    host.manifest = replacement(); host.blobsDown = true
    assertIs<RefreshResult.Failed>(r.refresh(force = true))
    assertEquals(1, r.current()?.serial)
    assertNotNull(r.resolve(q))
    val rollback = repo(Host(Fx.manifest()))
    assertIs<RefreshResult.Failed>(rollback.refresh(force = true)).also { assertTrue("rollback" in it.reason) }
    host.blobsDown = false
    assertEquals(RefreshResult.Updated(2), r.refresh(force = true))
    assertEquals(2, r.current()?.serial)
  }

  @Test fun pendingReplacementRetriesAfterBackoffEvenWhileTheOldCatalogIsFresh() = runTest {
    val host = Host(Fx.manifest().copy(ttlSeconds = 3600))
    val r = repo(host)
    r.refresh()
    host.manifest = replacement(); host.blobsDown = true
    assertIs<RefreshResult.Failed>(r.refresh(force = true))
    assertEquals(RefreshResult.Skipped("backing off"), r.refresh())
    now += 60_001
    host.blobsDown = false
    assertEquals(RefreshResult.Updated(2), r.refresh(), "pending serial bypasses the old cache's TTL")
    assertEquals(2, r.current()?.serial)
  }

  @Test fun refreshWithdrawalsUseTheLatestPersistedPlayableSnapshot() = runTest {
    val host = Host(Fx.manifest())
    val first = repo(host)
    first.refresh()
    val second = repo(host)
    assertEquals(1, second.manifest()?.serial)
    host.manifest = replacement()
    assertEquals(RefreshResult.Updated(2), first.refresh(force = true))
    val latest = assertNotNull(first.resolve(q))
    host.manifest = replacement().copy(serial = 3,
      designs = replacement().designs.map { it.copy(revoked = it.id != "tile-default") },
      templates = replacement().templates.map {
        if (it.target.surface == "launcher-widget") it.copy(sha256 = FakeCrypto.sha(Fx.blob("missing"))) else it
      })
    assertIs<RefreshResult.Failed>(second.refresh(force = true))
    assertEquals(2, second.manifest()?.serial, "withdrawals must retain the newest activated snapshot")
    val reopened = repo(host)
    assertEquals(2, reopened.manifest()?.serial)
    assertEquals(latest.entry.sha256, reopened.resolve(q)?.entry?.sha256)
    assertTrue(reopened.manifest()!!.designs.filter { it.id != "tile-default" }.all { it.revoked })
  }

  @Test fun designRevocationAppliesBeforeReplacementDownloadsFinish() = runTest { checkPendingRevocation(true) }

  @Test fun templateRevocationAppliesBeforeReplacementDownloadsFinish() = runTest { checkPendingRevocation(false) }

  private suspend fun kotlinx.coroutines.CoroutineScope.checkPendingRevocation(design: Boolean) {
    val original = Fx.manifest()
    val host = Host(original)
    val store = MemoryBlobStore()
    val values = MemoryKeyValueStore()
    val bundled = TemplateRepository.Bundled(original) { sha ->
      listOf("default-v7", "default-v8", "default-launcher").map(Fx::blob).firstOrNull { FakeCrypto.sha(it) == sha }
    }
    fun open() = TemplateRepository(StaticSource("https://cdn.example/catalog/main"), host, FakeCrypto,
      TrustPolicy.Signed(listOf(TrustedKey("k", key))), store, values, { now }, listOf(Fx.inApp), bundled)
    val r = open()
    r.refresh()
    val old = assertNotNull(r.resolve(q))
    host.manifest = original.copy(serial = 2,
      designs = original.designs.map {
        when (it.id) {
          "tile-default" -> it.copy(revoked = design)
          else -> it.copy(install = ee.schimke.livery.protocol.InstallPolicy.Always)
        }
      },
      templates = original.templates.map {
        if (it.designId == "tile-default") it.copy(revoked = !design)
        else it.copy(sha256 = FakeCrypto.sha(Fx.blob("missing")))
      })
    host.blobRequested = CompletableDeferred()
    host.resumeBlobs = CompletableDeferred()
    val refreshing = async { r.refresh(force = true) }
    host.blobRequested!!.await()
    suspend fun withdrawn(repository: TemplateRepository) {
      assertNull(repository.resolve(q), "withdrawn content must not return through bundled fallback")
      assertNull(repository.ensure(TemplateRef(sha256 = old.entry.sha256), q))
      assertNull(repository.bundledTemplate(old.entry.sha256, q))
      if (design) assertFalse(repository.automaticallySelectable("tile-default"))
    }
    withdrawn(r)
    withdrawn(open())
    host.resumeBlobs!!.complete(Unit)
    assertIs<RefreshResult.Failed>(refreshing.await())
    withdrawn(open())
    now = original.expiresAtEpochMs + TemplateRepository.STALE_GRACE_MS + 1
    withdrawn(open())
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

  @Test fun cacheOnlyEnsureDownloadsNothingAndRecordsTheMiss() = runTest {
    val host = Host(Fx.manifest())
    val r = repo(host)
    r.refresh()
    val retro = Fx.manifest().templates.first { it.designId == "tile-retro" && it.target == Fx.inAppV7 }
    val ref = TemplateRef(sha256 = retro.sha256)
    val before = host.requests.size
    val cacheOnly = CacheOnly()
    assertNull(kotlinx.coroutines.withContext(cacheOnly) { r.ensure(ref, q) }, "not on the device: unavailable now")
    assertEquals(before, host.requests.size, "and nothing was fetched")
    assertEquals(1, cacheOnly.misses)
    assertTrue(cacheOnly.fetchMissed(), "fetched off the render path")
    assertNotNull(kotlinx.coroutines.withContext(CacheOnly()) { r.ensure(ref, q) }, "the next render plays it")
  }

  @Test fun revisionMovesWhenARetryDownloadsWhatAnEarlierRefreshMissed() = runTest {
    val host = Host(Fx.manifest())
    val r = repo(host)
    host.blobsDown = true
    r.refresh()
    assertEquals(1, r.revision.value, "catalog accepted, templates missing")
    host.blobsDown = false
    r.refresh() // fresh: only the downloads retry
    assertEquals(2, r.revision.value, "the missing templates arrived")
    r.refresh()
    assertEquals(2, r.revision.value, "nothing new")
  }

  @Test fun fallsBackToBundledWhenNothingIsCached() = runTest {
    val bundledManifest = Fx.manifest()
    val r = repo(Host(Fx.manifest()).apply { down = true }, TemplateRepository.Bundled(bundledManifest) { sha ->
      listOf("default-v8").map(Fx::blob).firstOrNull { FakeCrypto.sha(it) == sha }
    })
    r.refresh()
    assertEquals(LoadedTemplate.Origin.Bundled, r.resolve(q)?.origin)
  }

  @Test fun placementsAndThemesComeFromTheBundledCatalogBeforeAnyDownload() = runTest {
    val pack = ThemePack("dusk", "Dusk", "dark", mapOf("theme.primary" to NamedValue.Color(0xFF102030)))
    val rule = PlacementRule("spring", "home.top", TemplateRef(designId = "default"), priority = 1)
    val bundledManifest = Fx.manifest().copy(themes = listOf(pack), placements = listOf(rule))
    val r = repo(Host(Fx.manifest()).apply { down = true }, TemplateRepository.Bundled(bundledManifest) { null })
    r.refresh()
    assertEquals(null, r.manifest(), "nothing downloaded")
    assertEquals(bundledManifest, r.current())
    assertEquals(NamedValue.Color(0xFF102030), r.theme("dusk")?.values?.get("theme.primary"))
    assertEquals(null, r.theme("dawn"))
    val decision = LocalRulesDecider({ r.current() }, MemoryFrequencyStore())
      .decide("home.top", DecisionContext(now, "android", "en", 1, "install"))
    assertEquals("local:spring", decision?.decisionId)
  }
}
