package ee.schimke.livery.web

import ee.schimke.livery.client.Clock
import ee.schimke.livery.client.FetchResult
import ee.schimke.livery.client.Fetcher
import ee.schimke.livery.client.JavaCrypto
import ee.schimke.livery.client.MemoryBlobStore
import ee.schimke.livery.client.MemoryKeyValueStore
import ee.schimke.livery.client.SlotPlanner
import ee.schimke.livery.client.StaticSource
import ee.schimke.livery.client.TemplateRepository
import ee.schimke.livery.client.TrustPolicy
import ee.schimke.livery.instantiate.AgentBytesPolicy
import ee.schimke.livery.instantiate.DocumentAdmission
import ee.schimke.livery.client.ImageResolver
import ee.schimke.livery.client.RedirectingFetcher
import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.livery.protocol.PlayerCapabilities
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.TemplateEvent
import ee.schimke.livery.publisher.CatalogBuilder
import ee.schimke.livery.publisher.Layout
import ee.schimke.livery.publisher.build
import java.io.File
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The web's Livery component pipeline over the sample catalogs, offline, as a first launch. */
class LiveryA2uiWebTest {
  private val samples = File("../samples")
  private val tree = Files.createTempDirectory("livery-web").toFile().also { tree ->
    File(samples, "catalog").copyRecursively(tree)
    for (dir in listOf("contracts", "designs")) File(samples, "two-way/$dir").copyRecursively(File(tree, dir))
  }
  private val built = CatalogBuilder(now = { 0 }).build(tree, Layout.Static, serial = 0)
  private val caps = PlayerCapabilities(Surfaces.IN_APP, maxRcApiLevel = 7, profiles = setOf("androidx"), livePush = false)
  private val clock = Clock { 1_000L }
  private val events = mutableListOf<TemplateEvent>()
  private val repository = TemplateRepository(
    StaticSource("https://catalog.invalid/"),
    Fetcher { _, _ -> FetchResult.Failed(503, "offline") },
    JavaCrypto, TrustPolicy.AllowUnsigned, MemoryBlobStore(), MemoryKeyValueStore(), clock, listOf(caps),
    TemplateRepository.Bundled(built.manifest) { sha -> built.files.entries.firstOrNull { it.key.contains(sha) }?.value },
  )
  private fun web(documents: DocumentAdmission? = null, images: ImageResolver? = null) = LiveryA2uiWeb(
    repository, SlotPlanner(repository, { events += it }, clock), caps, { events += it }, clock,
    images, documents, appKinds = setOf("navigate", "app:vote"),
  )

  private fun props(json: String) = Json.parseToJsonElement(json).jsonObject

  private val promo = props("""{"headline":"Spring sale","cta":"Shop now","actions":{"cta":{"event":{"name":"startTrial","context":{"from":"chat"}}}}}""")

  @Test fun advertisesThePlayableContracts() = runTest {
    val names = web().components().map { it.name }
    assertTrue(listOf("PromoBanner", "SocialPoll", "ShopQuantity").all { it in names }, names.toString())
  }

  @Test fun aDataChangeKeepsTheDocumentAndChangesItsValues() = runTest {
    val web = web()
    val qty = props("""{"name":"Socks","price":"£4","quantity":1}""")
    val r = assertIs<LiveryA2uiWeb.Rendered.Ready>(web.render("ShopQuantity", qty, LiveryA2uiWeb.Space(widthDp = 360)))
    // The template as published, its values beside it: the player keeps the document and sets them.
    assertEquals(built.files.entries.first { it.key.contains(r.sha256) }.value.toList(), r.document.toList())
    assertEquals(JsonPrimitive("Socks"), r.values["name"])
    assertEquals(JsonPrimitive(1), r.values["quantity"])
    val again = assertIs<LiveryA2uiWeb.Rendered.Ready>(web.render("ShopQuantity", JsonObject(qty + ("quantity" to JsonPrimitive(3))), LiveryA2uiWeb.Space(widthDp = 360)))
    assertEquals(r.documentKey, again.documentKey, "the same document: only a value changed")
    assertEquals(JsonPrimitive(3), again.values["quantity"])
    web.shown(again.id)
    assertEquals(TemplateEvent.Type.Impression, events.last().type)
    assertEquals(again.designId, events.last().designId)
  }

  @Test fun coloursAndBooleansAreSetAsThePlayerTakesThem() = runTest {
    val r = web().render("LiveStatusTile", props("""{"name":"Lamp","state":"On","is_on":true}"""), LiveryA2uiWeb.Space(themeArgb = mapOf("primary" to 0xFF6750A4L)))
    val ready = assertIs<LiveryA2uiWeb.Rendered.Ready>(r, r.toString())
    assertEquals(JsonPrimitive(1), ready.values["is_on"])
    assertEquals(JsonPrimitive("#FF6750A4"), ready.values["theme.primary"])
  }

  @Test fun dataThatDoesNotBindFallsBack() = runTest {
    val r = web().render("PromoBanner", props("""{"headline":"${"x".repeat(200)}","cta":"Go"}"""))
    assertTrue(assertIs<LiveryA2uiWeb.Rendered.Fallback>(r).reason.contains("invalid data"), r.toString())
    // As on every client, the reason for the agent is short; the per-input problems are in the event.
    assertTrue(events.any { it.detail.orEmpty().contains("headline") }, events.toString())
    assertIs<LiveryA2uiWeb.Rendered.Fallback>(web().render("Nope", promo))
  }

  @Test fun aWiredActionGoesToTheAgentWithItsCheckedArguments() = runTest {
    val web = web()
    val r = web.render("PromoBanner", promo) as LiveryA2uiWeb.Rendered.Ready
    val routed = assertIs<LiveryA2uiWeb.Routed.Agent>(web.action(r.id, "cta", "string", """{"route":"/premium","extra":"x"}"""))
    val context = routed.action["event"]!!.jsonObject["context"]!!.jsonObject
    assertEquals(JsonPrimitive("/premium"), context["route"])
    assertEquals(JsonPrimitive("chat"), context["from"])
    assertTrue("extra" !in context, "an undeclared argument never reaches the agent")
    assertEquals(TemplateEvent.Type.Action, events.last().type)
  }

  @Test fun anUnwiredActionGoesToThePageOrIsDropped() = runTest {
    val web = web()
    val r = web.render("PromoBanner", JsonObject(promo - "actions")) as LiveryA2uiWeb.Rendered.Ready
    assertEquals(LiveryA2uiWeb.Routed.App("navigate", "cta", mapOf("route" to "/premium")), web.action(r.id, "cta", "string", """{"route":"/premium"}"""))
    // `dismiss` has no handler on this page: dropped, and reported.
    assertIs<LiveryA2uiWeb.Routed.Dropped>(web.action(r.id, "dismiss", "none", null))
    assertEquals(TemplateEvent.Type.Error, events.last().type)
    assertIs<LiveryA2uiWeb.Routed.Dropped>(web.action(r.id, "undeclared", "none", null))
    assertIs<LiveryA2uiWeb.Routed.Dropped>(web.action(999, "cta", "none", null))
  }

  @Test fun aSetWritesTheInputBackAsItsType() = runTest {
    val web = web()
    val r = web.render("ShopQuantity", props("""{"name":"Socks","quantity":{"path":"/q"}}""".replace("""{"path":"/q"}""", "1")))
      as LiveryA2uiWeb.Rendered.Ready
    val set = assertIs<LiveryA2uiWeb.Routed.Set>(web.action(r.id, "setQuantity", "int", "3"))
    assertEquals("quantity", set.input)
    assertEquals(JsonPrimitive(3L), set.value)
    assertEquals(null, set.agent)
    assertEquals(JsonNull, (web.action(r.id, "setQuantity", "string", "three") as? LiveryA2uiWeb.Routed.Set)?.value ?: JsonNull)
  }

  @Test fun anInlineDocumentPlaysOnlyWhenAdmitted() = runTest {
    // The document the catalog plays for this data (some sample files are placeholders, not documents).
    val chosen = web().render("PromoBanner", promo) as LiveryA2uiWeb.Rendered.Ready
    val bytes = built.files.entries.first { it.key.contains(chosen.sha256) }.value
    val withDoc = JsonObject(promo + ("template" to props("""{"inline":"${Base64.encode(bytes)}"}""")))
    assertTrue("no documents" in (web().render("PromoBanner", withDoc) as LiveryA2uiWeb.Rendered.Fallback).reason)
    val admitting = web(DocumentAdmission(JavaCrypto, emptyList(), agentBytes = AgentBytesPolicy(setOf("promo/"), maxBytes = 1 shl 20)))
    val r = admitting.render("PromoBanner", withDoc).let { assertIs<LiveryA2uiWeb.Rendered.Ready>(it, it.toString()) }
    assertEquals("a2ui:unsigned", r.designId)
    val malformed = JsonObject(promo + ("template" to JsonPrimitive("nope")))
    assertIs<LiveryA2uiWeb.Rendered.Fallback>(admitting.render("PromoBanner", malformed), "a malformed template is refused, never answered from the catalog")
  }

  @Test fun anImageNotOnTheDeviceWaitsAndIsFetchedOffTheRenderPath() = runTest {
    // The catalog's promo document with an image bound to `hero`, sent as an admitted inline document.
    val chosen = web().render("PromoBanner", promo) as LiveryA2uiWeb.Rendered.Ready
    val plain = built.files.entries.first { it.key.contains(chosen.sha256) }.value
    val d = RcDocumentCodec.decode(plain, RcWireLimits())
    val withImage = RcDocumentCodec.encode(d.copy(operations = listOf(RcBitmapData(9000, 1, 1, 0, RcBitmapData.ENCODING_URL, "livery:value/hero".encodeToByteArray())) + d.operations))
    val png = File(samples, "catalog/designs/banner-bold/assets/hero.png").readBytes()
    val fetched = mutableListOf<String>()
    val fetcher = object : RedirectingFetcher {
      override suspend fun get(url: String, etag: String?) = get(url, etag, Long.MAX_VALUE) { false }
      override suspend fun get(url: String, etag: String?, maxBytes: Long, allowRedirect: (String) -> Boolean): FetchResult {
        fetched += url
        return FetchResult.Ok(png, null)
      }
    }
    val web = web(
      DocumentAdmission(JavaCrypto, emptyList(), agentBytes = AgentBytesPolicy(setOf("promo/"), maxBytes = 1 shl 20)),
      ImageResolver(repository, fetcher, JavaCrypto, directHosts = setOf("img.example")),
    )
    val props = JsonObject(promo + ("hero" to JsonPrimitive("https://img.example/hero.png")) + ("template" to props("""{"inline":"${Base64.encode(withImage)}"}""")))

    assertIs<LiveryA2uiWeb.Rendered.Waiting>(web.render("PromoBanner", props, LiveryA2uiWeb.Space(widthDp = 360)))
    assertEquals(emptyList(), fetched, "rendering never waits on the network")
    assertTrue(web.fetchImages())
    assertEquals(1, fetched.size)
    val ready = assertIs<LiveryA2uiWeb.Rendered.Ready>(web.render("PromoBanner", props, LiveryA2uiWeb.Space(widthDp = 360)), "the image is on the device now")
    assertTrue("hero" !in ready.values, "an image is written into the document, never set as a value")
    assertTrue("hero" in ready.documentKey, "so a new image is a new document")
    assertEquals(1, fetched.size, "and the second render used it from there")
    assertEquals(false, web.fetchImages(), "nothing left to fetch")
  }

  @Test fun everyRenderStillOnScreenCanAct() = runTest {
    val web = web()
    val first = web.render("PromoBanner", promo) as LiveryA2uiWeb.Rendered.Ready
    repeat(100) { web.render("PromoBanner", promo) }
    assertIs<LiveryA2uiWeb.Routed.Agent>(web.action(first.id, "cta", "string", """{"route":"/a"}"""), "no plan is dropped while its render is on screen")
    web.release(first.id)
    assertIs<LiveryA2uiWeb.Routed.Dropped>(web.action(first.id, "cta", "string", """{"route":"/a"}"""))
  }
}
