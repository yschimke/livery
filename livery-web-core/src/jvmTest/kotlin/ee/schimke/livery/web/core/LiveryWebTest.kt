package ee.schimke.livery.web.core

import ee.schimke.livery.client.*
import ee.schimke.livery.protocol.*
import ee.schimke.livery.publisher.CatalogBuilder
import ee.schimke.livery.publisher.Layout
import ee.schimke.livery.publisher.build
import java.io.File
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class LiveryWebTest {
  private val built = CatalogBuilder(now = { 0 }).build(File(requireNotNull(System.getProperty("livery.samples.two-way")) { "run through Gradle: gradle/sample-catalogs.gradle.kts" }), Layout.Static, serial = 0)
  private val caps = PlayerCapabilities(Surfaces.IN_APP, 7, profiles = setOf("androidx"))
  private val clock = Clock { 1000L }
  private val events = mutableListOf<TemplateEvent>()
  private val data = Json.parseToJsonElement("""{"name":"Oat milk","quantity":1}""").jsonObject
  private val entry = built.manifest.templates.first()
  private suspend fun web(bundled: Boolean = true): LiveryWeb {
    var refreshing = true
    val fetcher = Fetcher { url, _ ->
      check(refreshing) { "render must not fetch" }
      when {
        url.endsWith("manifest.json") -> FetchResult.Ok(Json.encodeToString(Manifest.serializer(), built.manifest).encodeToByteArray(), null)
        bundled -> built.files.entries.firstOrNull { url.endsWith(it.key) }?.value?.let { FetchResult.Ok(it, null) } ?: FetchResult.Failed(404, "missing")
        else -> FetchResult.Failed(503, "offline")
      }
    }
    val repo = TemplateRepository(StaticSource("https://preview.invalid/catalog/"), fetcher, JavaCrypto, TrustPolicy.AllowUnsigned,
      MemoryBlobStore(), MemoryKeyValueStore(), clock, listOf(caps))
    repo.refresh()
    refreshing = false
    return LiveryWeb(repo, SlotPlanner(repo, { events += it }, clock), caps, { events += it }, clock)
  }
  @Test fun pinnedFixtureUpdatesValuesAndRejectsInvalidWrites() = runTest {
    val web = web()
    val first = assertIs<LiveryWeb.Rendered.Ready>(web.render("shop/quantity@1", data, designId = entry.designId, sha256 = entry.sha256))
    assertEquals(entry.sha256, first.sha256)
    val set = assertIs<LiveryWeb.Routed.Set>(web.action(first.id, "setQuantity", "string", "2"))
    assertEquals(JsonPrimitive(2), set.value)
    val next = assertIs<LiveryWeb.Rendered.Ready>(web.render("shop/quantity@1", JsonObject(data + (set.input to set.value)), designId = entry.designId, sha256 = entry.sha256))
    assertEquals(first.documentKey, next.documentKey)
    assertIs<LiveryWeb.Routed.Dropped>(web.action(first.id, "setQuantity", "string", "10"))
    assertIs<LiveryWeb.Routed.Dropped>(web.action(first.id, "bookmark", "none", null))
    web.close()
    assertIs<LiveryWeb.Routed.Dropped>(web.action(next.id, "setQuantity", "string", "3"))
  }
  @Test fun pinMismatchAndBadDataNeverPlayAnotherTemplate() = runTest {
    val web = web()
    assertIs<LiveryWeb.Rendered.Fallback>(web.render("shop/quantity@1", data, sha256 = "0".repeat(64)))
    assertIs<LiveryWeb.Rendered.Fallback>(web.render("shop/quantity@1", data, designId = "wrong", sha256 = entry.sha256))
    assertIs<LiveryWeb.Rendered.Fallback>(web.render("shop/quantity@1", JsonObject(data + ("quantity" to JsonPrimitive(10))), sha256 = entry.sha256))
  }
  @Test fun uncachedPinnedBlobWaitsWithoutNetworking() = runTest {
    val web = web(bundled = false)
    assertIs<LiveryWeb.Rendered.Waiting>(web.render("shop/quantity@1", data, sha256 = entry.sha256))
  }
}
