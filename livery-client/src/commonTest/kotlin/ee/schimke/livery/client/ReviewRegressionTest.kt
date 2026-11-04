package ee.schimke.livery.client

import ee.schimke.livery.protocol.Decision
import ee.schimke.livery.protocol.Manifest
import ee.schimke.livery.protocol.TemplateRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
  private fun blobFor(sha: String) = names.map(Fx::blob).firstOrNull { FakeCrypto.sha256Hex(it) == sha }

  /** Serves [manifest] unsigned; [gate], when set, holds the manifest request until completed. */
  private class Host(val manifest: Manifest, val blob: (String) -> ByteArray?) : Fetcher {
    var gate: CompletableDeferred<Unit>? = null
    var down = false
    override suspend fun get(url: String, etag: String?): FetchResult {
      if (down) return FetchResult.Failed(503, "down")
      if (url.endsWith("manifest.json")) {
        gate?.await()
        return FetchResult.Ok(Json.encodeToString(Manifest.serializer(), manifest).encodeToByteArray(), "e")
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
