package ee.schimke.livery.client

import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import ee.schimke.livery.protocol.Decision
import ee.schimke.livery.protocol.Manifest
import ee.schimke.livery.protocol.Target
import ee.schimke.livery.protocol.TemplateEntry
import ee.schimke.livery.protocol.TemplateRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Regressions from the first review of the client: exact references, offline binding, locking. */
class ReviewRegressionTest {
  private val m = Fx.manifest()
  private fun sha(i: Int) = m.templates[i].sha256
  private val names = listOf("default-v7", "default-v8", "default-v9", "default-launcher", "default-wear-small", "retro-v7", "retro-launcher")
  private fun blobFor(sha: String) = names.map(Fx::blob).firstOrNull { FakeCrypto.sha(it) == sha }

  /** Serves [manifest] unsigned; [gate], when set, holds the manifest request until completed. */
  private class Host(val manifest: Manifest, val blob: (String) -> ByteString?) : Fetcher {
    var gate: CompletableDeferred<Unit>? = null
    var down = false
    override suspend fun get(url: String, etag: String?): FetchResult {
      if (down) return FetchResult.Failed(503, "down")
      if (url.endsWith("manifest.json")) {
        gate?.await()
        return FetchResult.Ok(Json.encodeToString(Manifest.serializer(), manifest).encodeUtf8(), "e")
      }
      if (url.endsWith(".sig.json")) return FetchResult.Failed(404, "unsigned")
      return blob(url.substringAfterLast('/').removeSuffix(".rc"))?.let { FetchResult.Ok(it, null) } ?: FetchResult.Failed(404, "no blob")
    }
  }

  private var now = 1_000L
  private fun repo(host: Host, bundled: TemplateRepository.Bundled? = null) = TemplateRepository(
    StaticSource("https://cdn.example/c"), host, FakeCrypto, TrustPolicy.AllowUnsigned,
    MemoryBlobStore(), MemoryKeyValueStore(), { now }, listOf(Fx.inApp.copy(maxRcApiLevel = 8), Fx.launcher), bundled,
  )

  private val inApp8 = Fx.inApp.copy(maxRcApiLevel = 8)
  private val q = TemplateQuery("live/status-tile@1", inApp8)

  @Test fun exactReferencesPassTheSelectionGate() = runTest {
    val r = repo(Host(m, ::blobFor)).also { it.refresh() }
    assertNull(r.ensure(TemplateRef(sha256 = sha(2)), q), "API 9 document for an API 8 player")
    assertNull(r.ensure(TemplateRef(sha256 = sha(3)), q), "launcher document for an in-app player")
    assertEquals(sha(1), r.ensure(TemplateRef(sha256 = sha(1)), q)?.entry?.sha256)
    val revokedDesign = m.copy(designs = m.designs.map { it.copy(revoked = it.id == "tile-default") })
    val r2 = repo(Host(revokedDesign, ::blobFor)).also { it.refresh() }
    assertNull(r2.ensure(TemplateRef(sha256 = sha(1)), q), "template of a revoked design")
  }

  @Test fun aPinnedDecisionIsNeverDrawnInAnotherTemplate() = runTest {
    // The pinned blob can't be downloaded: no substitute, so the decision's data never rides another creative.
    val host = Host(m) { s -> if (s == sha(5)) null else blobFor(s) }
    val r = repo(host).also { it.refresh() }
    val planner = SlotPlanner(r, {}, { now })
    val decision = Decision("home.top", "d1", TemplateRef(sha256 = sha(5)), data = Json.parseToJsonElement("""{"name":"Ad"}""") as JsonObject)
    assertIs<PlanResult.Fallback>(planner.forDecision(q, decision))
    val unknown = decision.copy(template = TemplateRef(sha256 = "0".repeat(64)))
    assertIs<PlanResult.Fallback>(planner.forDecision(q, unknown))
  }

  @Test fun bundledTemplatesBindOfflineAgainstTheirOwnCatalog() = runTest {
    val r = repo(Host(m, ::blobFor).apply { down = true }, TemplateRepository.Bundled(m) { blobFor(it) })
    r.refresh()
    assertNull(r.manifest())
    val plan = SlotPlanner(r, {}, { now }).forAppData(q, Json.parseToJsonElement("""{"name":"Kitchen"}""") as JsonObject)
    assertEquals(LoadedTemplate.Origin.Bundled, assertIs<PlanResult.Ready>(plan).plan.template.origin)
  }

  @Test fun renderingDoesNotWaitForARefreshInFlight() = runTest {
    val host = Host(m, ::blobFor)
    val r = repo(host)
    r.refresh()
    assertNotNull(r.resolve(q))
    host.gate = CompletableDeferred()
    val refresh = async { r.refresh(force = true) }
    yield() // the refresh is now suspended inside the manifest request
    assertNotNull(r.resolve(q), "resolve completed while the refresh held the network")
    host.gate!!.complete(Unit)
    assertIs<RefreshResult.Updated>(refresh.await())
  }
}

/** Regressions from the second review: cached fallbacks, shared stores, stale deadlines. */
class SecondReviewRegressionTest {
  private val m = Fx.manifest()
  private val names = listOf("default-v7", "default-v8", "default-v9", "default-launcher", "default-wear-small", "retro-v7", "retro-launcher")
  private fun blobFor(sha: String) = names.map(Fx::blob).firstOrNull { FakeCrypto.sha(it) == sha }
  private val inApp8 = Fx.inApp.copy(maxRcApiLevel = 8)
  private val q = TemplateQuery("live/status-tile@1", inApp8)
  private var now = 1_000L

  private class Host(val manifest: Manifest, val blob: (String) -> ByteString?) : Fetcher {
    override suspend fun get(url: String, etag: String?): FetchResult = when {
      url.endsWith("manifest.json") -> FetchResult.Ok(Json.encodeToString(Manifest.serializer(), manifest).encodeUtf8(), null)
      url.endsWith(".sig.json") -> FetchResult.Failed(404, "unsigned")
      else -> blob(url.substringAfterLast('/').removeSuffix(".rc"))?.let { FetchResult.Ok(it, null) } ?: FetchResult.Failed(404, "no blob")
    }
  }

  private fun repo(host: Fetcher, blobs: BlobStore = MemoryBlobStore(), source: String = "https://cdn.example/c", m: Manifest = this.m) = TemplateRepository(
    StaticSource(source), host, FakeCrypto, TrustPolicy.AllowUnsigned, blobs, MemoryKeyValueStore(), { now }, listOf(inApp8), null,
  )

  @Test fun aMissingPreferredBlobDoesNotHideACachedOne() = runTest {
    val v8 = m.templates[1].sha256
    val r = repo(Host(m) { if (it == v8) null else blobFor(it) }) // the API 8 download fails
    r.refresh()
    assertEquals(m.templates[0].sha256, r.resolve(q)?.entry?.sha256, "the cached API 7 template plays")
  }

  @Test fun pruningOneCatalogKeepsAnotherCatalogsBlobs() = runTest {
    val shared = MemoryBlobStore()
    val a = repo(Host(m, ::blobFor), shared, "https://a.example/c").also { it.refresh() }
    val onlyRetro = m.copy(designs = m.designs.map { if (it.id == "tile-retro") it.copy(install = ee.schimke.livery.protocol.InstallPolicy.Always) else it.copy(revoked = true) })
    val b = repo(Host(onlyRetro, ::blobFor), shared, "https://b.example/c").also { it.refresh() }
    val bNeeds = b.retained()
    a.prune()
    assertTrue(shared.keys().containsAll(bNeeds), "a's prune left b's blobs")
    TemplateRepository.prune(listOf(a, b))
    assertTrue(shared.keys().containsAll(a.retained() + bNeeds))
  }

  @Test fun explicitReferencesHonourTheStaleDeadline() = runTest {
    val r = repo(Host(m, ::blobFor)).also { it.refresh() }
    now = m.expiresAtEpochMs + 1
    assertEquals(LoadedTemplate.Origin.Stale, r.ensure(TemplateRef(sha256 = m.templates[1].sha256), q)?.origin)
    now = m.expiresAtEpochMs + TemplateRepository.STALE_GRACE_MS + 1
    assertNull(r.resolve(q))
    assertNull(r.ensure(TemplateRef(sha256 = m.templates[1].sha256), q))
    assertNull(r.ensure(TemplateRef(designId = "tile-default"), q))
  }
}

/** Regressions from the third review. */
class ThirdReviewRegressionTest {
  @Test fun bundledSelectionNeverPicksAnUninstalledSkin() = runTest {
    // The bundled skin is the only API 8 template, so selection without the policy would pick it.
    val m = Fx.manifest().let { m -> m.copy(templates = m.templates.filterNot { it.designId == "tile-default" && it.target.rcApiLevel == 8 } + Fx.entry("tile-retro", "retro-v8", Fx.inAppV8)) }
    val names = listOf("default-v7", "retro-v8")
    val repo = TemplateRepository(
      StaticSource("https://cdn.example/c"), { _, _ -> FetchResult.Failed(503, "down") }, FakeCrypto, TrustPolicy.AllowUnsigned,
      MemoryBlobStore(), MemoryKeyValueStore(), { 1_000 }, listOf(Fx.inApp), TemplateRepository.Bundled(m) { sha -> names.map(Fx::blob).firstOrNull { FakeCrypto.sha(it) == sha } },
    )
    val q = TemplateQuery("live/status-tile@1", Fx.inApp.copy(maxRcApiLevel = 8))
    assertEquals("tile-default", repo.resolve(q)?.entry?.designId)
    assertEquals("tile-retro", repo.resolve(q.copy(designId = "tile-retro"))?.entry?.designId, "an explicit choice still plays")
  }
}

/** Regressions from the last review of the first PR. */
class FourthReviewRegressionTest {
  @Test fun aPinnedBlobListedForSeveralTargetsResolvesForTheRightOne() = runTest {
    val base = Fx.manifest()
    val shared = Fx.blob("shared")
    val sha = FakeCrypto.sha(shared)
    val web = Target(ee.schimke.livery.protocol.Surfaces.WEB, 7, listOf("core"))
    val m = base.copy(templates = base.templates + listOf(
      TemplateEntry(sha, shared.size.toLong(), "tile-retro", "live/status-tile@1", web),
      TemplateEntry(sha, shared.size.toLong(), "tile-default", "live/status-tile@1", Fx.inAppV7),
    ))
    val host = Fetcher { url, _ ->
      when {
        url.endsWith("manifest.json") -> FetchResult.Ok(Json.encodeToString(Manifest.serializer(), m).encodeUtf8(), null)
        url.endsWith("$sha.rc") -> FetchResult.Ok(shared, null)
        else -> FetchResult.Failed(404, "no")
      }
    }
    val repo = TemplateRepository(StaticSource("https://cdn.example/c"), host, FakeCrypto, TrustPolicy.AllowUnsigned, MemoryBlobStore(), MemoryKeyValueStore(), { 1_000 }, emptyList())
    repo.refresh()
    val q = TemplateQuery("live/status-tile@1", Fx.inApp.copy(maxRcApiLevel = 7))
    assertEquals("tile-default", repo.ensure(TemplateRef(sha256 = sha), q)?.entry?.designId, "the in-app entry, not the first (web) one")
    assertEquals("tile-default", repo.ensure(TemplateRef(designId = "tile-default", sha256 = sha), q)?.entry?.designId)
    assertNull(repo.ensure(TemplateRef(designId = "tile-retro", sha256 = sha), q), "that design lists those bytes only for the web")
  }
}
