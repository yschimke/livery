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
import ee.schimke.livery.instantiate.TemplateInstantiator
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
import kotlin.test.assertNotEquals
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
  private var now = 1_000L
  private val clock = Clock { now }
  private val events = mutableListOf<TemplateEvent>()
  private val repository = TemplateRepository(
    StaticSource("https://catalog.invalid/"),
    Fetcher { _, _ -> FetchResult.Failed(503, "offline") },
    JavaCrypto, TrustPolicy.AllowUnsigned, MemoryBlobStore(), MemoryKeyValueStore(), clock, listOf(caps),
    TemplateRepository.Bundled(built.manifest) { sha -> built.files.entries.firstOrNull { it.key.contains(sha) }?.value },
  )
  private fun web(documents: DocumentAdmission? = null, images: ImageResolver? = null, decider: ee.schimke.livery.client.Decider? = null) = LiveryA2uiWeb(
    repository, SlotPlanner(repository, { events += it }, clock), caps, { events += it }, clock,
    images, documents, appKinds = setOf("navigate", "app:vote"), decider = decider,
    decisionContext = { ee.schimke.livery.client.DecisionContext(1_000, "web", "en-GB", 1, "app-user") },
  )

  private fun props(json: String) = Json.parseToJsonElement(json).jsonObject

  private val promo = props("""{"headline":"Spring sale","cta":"Shop now","actions":{"cta":{"event":{"name":"startTrial","context":{"from":"chat"}}}}}""")


  @Test fun exactBundledHashPlaysAndUnknownHashNeverSubstitutes() = runTest {
    val web = web()
    val installed = assertIs<LiveryA2uiWeb.Rendered.Ready>(web.render("PromoBanner", promo))
    val exact = JsonObject(promo + ("template" to props("""{"sha256":"${installed.sha256}"}""")))
    assertEquals(installed.sha256, assertIs<LiveryA2uiWeb.Rendered.Ready>(web.render("PromoBanner", exact)).sha256)
    val missing = JsonObject(promo + ("template" to props("""{"sha256":"${"0".repeat(64)}"}""")))
    assertEquals(false, assertIs<LiveryA2uiWeb.Rendered.Fallback>(web.render("PromoBanner", missing)).pending)
  }

  @Test fun queryDrawsImmediatelyThenSwapsAndKeepsTheComponentEventId() = runTest {
    var calls = 0
    val decider = object : ee.schimke.livery.client.Decider {
      override suspend fun decide(placement: String, context: ee.schimke.livery.client.DecisionContext) = error("not a placement")
      override suspend fun decide(request: ee.schimke.livery.client.DecisionRequest): ee.schimke.livery.protocol.Decision {
        calls++
        assertEquals("promo/banner@1", request.query.contract)
        assertEquals(360, request.query.widthDp)
        assertEquals("en-GB", request.context.locale)
        assertEquals("sale", (request.params["offer"] as JsonPrimitive).content)
        return ee.schimke.livery.protocol.Decision("chat", "server:42", ee.schimke.livery.protocol.TemplateRef(designId = "banner-classic"), props("""{"headline":"From server","cta":"Go"}"""))
      }
    }
    val web = web(decider = decider)
    val p = JsonObject(promo + ("template" to props("""{"query":{"offer":"sale"}}""")))
    val space = LiveryA2uiWeb.Space(widthDp = 360, decisionId = "a2ui:chat/promo")
    val immediate = assertIs<LiveryA2uiWeb.Rendered.Ready>(web.render("PromoBanner", p, space))
    assertEquals(0, calls, "render never invokes the server")
    assertTrue(immediate.pending)
    assertEquals(JsonPrimitive("Spring sale"), immediate.values["headline"])
    assertTrue(web.fetchPending())
    val decided = assertIs<LiveryA2uiWeb.Rendered.Ready>(web.render("PromoBanner", p, space))
    assertEquals(immediate.documentKey, decided.documentKey, "the server reused the automatic document")
    assertNotEquals(immediate.presentationKey, decided.presentationKey, "the server decision needs its own impression")
    val updated = assertIs<LiveryA2uiWeb.Rendered.Ready>(web.render("PromoBanner", JsonObject(p + ("headline" to JsonPrimitive("Updated fallback"))), space))
    assertEquals(decided.presentationKey, updated.presentationKey, "the cached decision is still the same presentation")
    assertEquals(JsonPrimitive("From server"), decided.values["headline"])
    assertEquals(false, decided.pending)
    web.shown(decided.id)
    assertEquals("a2ui:chat/promo", events.last().decisionId)
    assertEquals("chat", events.last().placement)
    assertEquals(1, calls)
  }

  @Test fun absentUnsupportedAndInvalidDecisionsKeepAutomaticData() = runTest {
    for (decision in listOf<ee.schimke.livery.protocol.Decision?>(null,
      ee.schimke.livery.protocol.Decision("chat", "bad", ee.schimke.livery.protocol.TemplateRef(designId = "absent")),
      ee.schimke.livery.protocol.Decision("chat", "bad", ee.schimke.livery.protocol.TemplateRef(designId = "banner-classic"), props("""{"headline":42}""")),
      ee.schimke.livery.protocol.Decision("chat", "bad", ee.schimke.livery.protocol.TemplateRef(designId = "banner-classic", contract = "other@1")),
    )) {
      val decider = object : ee.schimke.livery.client.Decider {
        override suspend fun decide(placement: String, context: ee.schimke.livery.client.DecisionContext) = null
        override suspend fun decide(request: ee.schimke.livery.client.DecisionRequest) = decision
      }
      val web = web(decider = decider)
      val p = JsonObject(promo + ("template" to props("""{"query":{}}""")))
      web.render("PromoBanner", p)
      web.fetchPending()
      val ready = assertIs<LiveryA2uiWeb.Rendered.Ready>(web.render("PromoBanner", p))
      assertEquals(JsonPrimitive("Spring sale"), ready.values["headline"])
      assertEquals(false, ready.pending)
    }
  }
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
    assertEquals(r.presentationKey, again.presentationKey, "live data updates do not create a new presentation")
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

  @Test fun aDocumentNamedByItsHashPlaysWhenACatalogListsIt() = runTest {
    val chosen = web().render("PromoBanner", promo) as LiveryA2uiWeb.Rendered.Ready
    val named = web().render("PromoBanner", JsonObject(promo + ("template" to props("""{"sha256":"${chosen.sha256}"}"""))))
    assertEquals(chosen.sha256, assertIs<LiveryA2uiWeb.Rendered.Ready>(named, named.toString()).sha256)
    assertEquals("banner-classic", named.designId, "the catalog's own entry: its design, its events")
    // A hash no catalog lists, with nothing to vouch for it, is refused: never another template in its place.
    val unknown = web().render("PromoBanner", JsonObject(promo + ("template" to props("""{"sha256":"${"0".repeat(64)}"}"""))))
    assertTrue("no trusted catalog" in assertIs<LiveryA2uiWeb.Rendered.Fallback>(unknown).reason, unknown.toString())
  }

  @Test fun aHashNamesThatExactEntryNotItsDesignsPreferredOne() = runTest {
    // banner-classic with a second, level-6 template (other bytes): a level-7 player prefers the
    // level-7 one, but a message that names the level-6 hash gets exactly that.
    val dir = Files.createTempDirectory("livery-web-levels").toFile().also { tree.copyRecursively(it) }
    val design = File(dir, "designs/banner-classic")
    val doc = RcDocumentCodec.decode(File(design, "in-app.rc").readBytes(), RcWireLimits())
    File(design, "in-app-v6.rc").writeBytes(RcDocumentCodec.encode(doc.copy(operations = doc.operations + ee.schimke.composeai.rcplayer.protocol.RcTextData(9001, "level 6"))))
    File(design, "in-app-v6.json").writeText(File(design, "in-app.json").readText().replace("\"rcApiLevel\": 7", "\"rcApiLevel\": 6"))
    val levels = CatalogBuilder(now = { 0 }).build(dir, Layout.Static, serial = 0)
    val v6 = levels.manifest.templates.single { it.designId == "banner-classic" && it.target.rcApiLevel == 6 }.sha256
    val repo = TemplateRepository(
      StaticSource("https://catalog.invalid/"), Fetcher { _, _ -> FetchResult.Failed(503, "offline") },
      JavaCrypto, TrustPolicy.AllowUnsigned, MemoryBlobStore(), MemoryKeyValueStore(), clock, listOf(caps),
      TemplateRepository.Bundled(levels.manifest) { sha -> levels.files.entries.firstOrNull { it.key.contains(sha) }?.value },
    )
    val web = LiveryA2uiWeb(repo, SlotPlanner(repo, { events += it }, clock), caps, { events += it }, clock)
    val preferred = web.render("PromoBanner", promo) as LiveryA2uiWeb.Rendered.Ready
    assertTrue(preferred.sha256 != v6, "the player's own choice is the level-7 one")
    val named = web.render("PromoBanner", JsonObject(promo + ("template" to props("""{"sha256":"$v6"}"""))))
    assertEquals(v6, assertIs<LiveryA2uiWeb.Rendered.Ready>(named, named.toString()).sha256)

    // A downloaded catalog that doesn't list it (the sample's own) doesn't make the bundled one unreachable.
    val online = TemplateRepository(
      StaticSource("https://catalog.invalid/"),
      Fetcher { url, _ -> if (url.endsWith("/manifest.json")) FetchResult.Ok(built.manifestBytes, null) else built.files.entries.firstOrNull { url.endsWith("/" + it.key) }?.let { FetchResult.Ok(it.value, null) } ?: FetchResult.Failed(404, url) },
      JavaCrypto, TrustPolicy.AllowUnsigned, MemoryBlobStore(), MemoryKeyValueStore(), clock, listOf(caps),
      TemplateRepository.Bundled(levels.manifest) { sha -> levels.files.entries.firstOrNull { it.key.contains(sha) }?.value },
    )
    online.refresh()
    assertTrue(online.current()!!.templates.none { it.sha256 == v6 }, "the downloaded catalog is in use, without v6")
    val fromBundle = LiveryA2uiWeb(online, SlotPlanner(online, { events += it }, clock), caps, { events += it }, clock)
      .render("PromoBanner", JsonObject(promo + ("template" to props("""{"sha256":"$v6"}"""))))
    assertEquals(v6, assertIs<LiveryA2uiWeb.Rendered.Ready>(fromBundle, fromBundle.toString()).sha256)
  }

  @Test fun aSignedDocumentNamedByUrlIsFetchedOffTheRenderPathFromTheCatalogsHostOnly() = runTest {
    val pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val chosen = web().render("PromoBanner", promo) as LiveryA2uiWeb.Rendered.Ready
    // Other bytes than any catalog lists (a per-request document): the catalog's banner with one unused text more.
    val catalogBytes = built.files.entries.first { it.key.contains(chosen.sha256) }.value
    val doc = RcDocumentCodec.decode(catalogBytes, RcWireLimits())
    val bytes = RcDocumentCodec.encode(doc.copy(operations = doc.operations + ee.schimke.composeai.rcplayer.protocol.RcTextData(9001, "per request")))
    val sha = JavaCrypto.sha256Hex(bytes)
    val signature = Base64.encode(java.security.Signature.getInstance("Ed25519").run {
      initSign(pair.private); update(ee.schimke.livery.client.TemplateSignatures.message("promo/banner@1", sha)); sign()
    })
    val admission = DocumentAdmission(JavaCrypto, listOf(ee.schimke.livery.client.TrustedKey("app", pair.public.encoded.takeLast(32).toByteArray())))
    val fetched = mutableListOf<String>()
    var available = false
    val web = LiveryA2uiWeb(
      repository, SlotPlanner(repository, { events += it }, clock), caps, { events += it }, clock, null, admission,
      fetcher = object : RedirectingFetcher {
        override suspend fun get(url: String, etag: String?) = get(url, etag) { true }
        override suspend fun get(url: String, etag: String?, maxBytes: Long, allowRedirect: (String) -> Boolean): FetchResult {
          fetched += url
          // The catalog host redirects to another host: never followed.
          if (url.endsWith("/moved.rc")) return if (allowRedirect("https://elsewhere.example/doc.rc")) FetchResult.Ok(bytes, null) else FetchResult.Failed(null, "redirect refused")
          if (!available) return FetchResult.Failed(503, "temporarily offline")
          return if (bytes.size > maxBytes) FetchResult.Failed(200, "too big") else FetchResult.Ok(bytes, null)
        }
      },
      documentHosts = { it == "catalog.example" },
    )
    fun named(url: String) = JsonObject(promo + ("template" to props("""{"sha256":"$sha","url":"$url","signature":{"keyId":"app","signature":"$signature"}}""")))
    // The agent names content; it never makes the page contact a host the app didn't configure.
    assertIs<LiveryA2uiWeb.Rendered.Fallback>(web.render("PromoBanner", named("https://elsewhere.example/doc.rc")))
    // A catalog-host URL that redirects elsewhere: the redirect isn't followed, so nothing arrives.
    assertIs<LiveryA2uiWeb.Rendered.Waiting>(web.render("PromoBanner", named("https://catalog.example/moved.rc")))
    assertTrue(!web.fetchImages(), "a redirect off the catalog's hosts is refused")
    fetched.clear()
    assertIs<LiveryA2uiWeb.Rendered.Waiting>(web.render("PromoBanner", named("https://catalog.example/doc.rc")))
    assertTrue(fetched.isEmpty(), "nothing is fetched on the render path")
    assertTrue(!web.fetchImages(), "a transient failure shows the app's fallback")
    assertEquals(listOf("https://catalog.example/doc.rc"), fetched)
    available = true
    now += 29_999
    assertIs<LiveryA2uiWeb.Rendered.Fallback>(web.render("PromoBanner", named("https://catalog.example/doc.rc")))
    assertTrue(!web.fetchPending(), "failed requests back off instead of retrying on every render")
    assertEquals(1, fetched.size)
    now++
    assertIs<LiveryA2uiWeb.Rendered.Waiting>(web.render("PromoBanner", named("https://catalog.example/doc.rc")))
    assertEquals(1, fetched.size, "the retry still runs off the render path")
    assertTrue(web.fetchImages())
    assertEquals(listOf("https://catalog.example/doc.rc", "https://catalog.example/doc.rc"), fetched)
    val r = assertIs<LiveryA2uiWeb.Rendered.Ready>(web.render("PromoBanner", named("https://catalog.example/doc.rc")))
    assertEquals("a2ui:signed:app", r.designId)
    now += 30_000
    assertIs<LiveryA2uiWeb.Rendered.Ready>(web.render("PromoBanner", named("https://catalog.example/doc.rc")))
    assertTrue(!web.fetchPending(), "a successful signed document stays cached")
    assertEquals(2, fetched.size, "one failed attempt followed by one successful retry")
    // A page whose signed limit is smaller than the document never keeps it.
    val small = LiveryA2uiWeb(
      repository, SlotPlanner(repository, { events += it }, clock), caps, { events += it }, clock, null,
      DocumentAdmission(JavaCrypto, listOf(ee.schimke.livery.client.TrustedKey("app", pair.public.encoded.takeLast(32).toByteArray())), maxSignedBytes = 16),
      fetcher = object : RedirectingFetcher {
        override suspend fun get(url: String, etag: String?) = get(url, etag) { true }
        override suspend fun get(url: String, etag: String?, maxBytes: Long, allowRedirect: (String) -> Boolean): FetchResult =
          if (bytes.size > maxBytes) FetchResult.Failed(200, "more than $maxBytes bytes") else FetchResult.Ok(bytes, null)
      },
      documentHosts = { it == "catalog.example" },
    )
    assertIs<LiveryA2uiWeb.Rendered.Waiting>(small.render("PromoBanner", named("https://catalog.example/doc.rc")))
    assertTrue(!small.fetchImages(), "over the signed limit: not read, not kept")
  }

  @Test fun aTemplateSaysHowTallItIs() = runTest {
    // Authored fallback remains available when the player cannot measure a root.
    val r = web().render("PromoBanner", promo) as LiveryA2uiWeb.Rendered.Ready
    val bytes = built.files.entries.first { it.key.contains(r.sha256) }.value
    assertEquals(ee.schimke.livery.instantiate.TemplateInstantiator.authoredHeightDp(bytes), r.heightDp)
    assertEquals(160, r.heightDp, "banner-classic is authored at 360x160")
    assertEquals(null, r.minHeightDp, "authored fallback is not a target minimum")
  }

  @Test fun anExplicitMinimumRemainsSeparateFromMeasuredContent() = runTest {
    val manifest = built.manifest.copy(templates = built.manifest.templates.map { entry ->
      entry.copy(target = entry.target.copy(size = entry.target.size.copy(minHeightDp = 150)))
    })
    val repo = TemplateRepository(
      StaticSource("https://catalog.invalid/"), Fetcher { _, _ -> FetchResult.Failed(503, "offline") },
      JavaCrypto, TrustPolicy.AllowUnsigned, MemoryBlobStore(), MemoryKeyValueStore(), clock, listOf(caps),
      TemplateRepository.Bundled(manifest) { sha -> built.files.entries.firstOrNull { it.key.contains(sha) }?.value },
    )
    val web = LiveryA2uiWeb(repo, SlotPlanner(repo, { events += it }, clock), caps, { events += it }, clock)
    val ready = assertIs<LiveryA2uiWeb.Rendered.Ready>(web.render("PromoBanner", promo))
    assertEquals(150, ready.minHeightDp)
    assertEquals(150, ready.heightDp)
  }

  @Test fun itsEventsCarryWhereTheAgentPlacedIt() = runTest {
    val web = web()
    val r = web.render("PromoBanner", promo, LiveryA2uiWeb.Space(decisionId = "a2ui:chat-1/promo")) as LiveryA2uiWeb.Rendered.Ready
    web.shown(r.id)
    web.action(r.id, "cta", "string", """{"route":"/premium"}""")
    assertEquals(listOf(TemplateEvent.Type.Impression, TemplateEvent.Type.Action), events.takeLast(2).map { it.type })
    assertTrue(events.takeLast(2).all { it.decisionId == "a2ui:chat-1/promo" }, events.toString())
    // The planner's own events too: data that doesn't bind is reported for that component.
    events.clear()
    web.render("PromoBanner", props("""{"headline":"${"x".repeat(200)}","cta":"Go"}"""), LiveryA2uiWeb.Space(decisionId = "a2ui:chat-1/bad"))
    assertEquals(listOf("a2ui:chat-1/bad"), events.filter { it.type == TemplateEvent.Type.Error }.map { it.decisionId }, events.toString())
    // And the agent's own document.
    val bytes = built.files.entries.first { it.key.contains(r.sha256) }.value
    val admitting = web(DocumentAdmission(JavaCrypto, emptyList(), agentBytes = AgentBytesPolicy(setOf("promo/"), maxBytes = 1 shl 20)))
    val inline = admitting.render("PromoBanner", JsonObject(promo + ("template" to props("""{"inline":"${Base64.encode(bytes)}"}"""))), LiveryA2uiWeb.Space(decisionId = "a2ui:chat-1/doc"))
    admitting.shown(assertIs<LiveryA2uiWeb.Rendered.Ready>(inline).id)
    assertEquals("a2ui:chat-1/doc", events.last().decisionId)
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

  @Test fun aValueLeftOutReloadsTheDocumentSoItDrawsItsDefault() = runTest {
    val web = web()
    val withBody = web.render("PromoBanner", JsonObject(promo + ("body" to JsonPrimitive("Ends Sunday")))) as LiveryA2uiWeb.Rendered.Ready
    val without = web.render("PromoBanner", promo) as LiveryA2uiWeb.Rendered.Ready
    assertTrue(withBody.documentKey != without.documentKey, "the playing document would keep the old body otherwise")
    val again = web.render("PromoBanner", JsonObject(promo + ("headline" to JsonPrimitive("Summer sale")))) as LiveryA2uiWeb.Rendered.Ready
    assertEquals(without.documentKey, again.documentKey, "a changed value is still only a value")
  }

  @Test fun aStaticImageIsWrittenInEvenWithNoImageInput() = runTest {
    // A quantity document (no image input) that draws a fixed https image.
    val chosen = web().render("ShopQuantity", props("""{"name":"Socks"}""")) as LiveryA2uiWeb.Rendered.Ready
    val plain = built.files.entries.first { it.key.contains(chosen.sha256) }.value
    val d = RcDocumentCodec.decode(plain, RcWireLimits())
    val withImage = RcDocumentCodec.encode(d.copy(operations = listOf(RcBitmapData(9000, 1, 1, 0, RcBitmapData.ENCODING_URL, "https://img.example/logo.png".encodeToByteArray())) + d.operations))
    val fetched = mutableListOf<String>()
    val fetcher = object : RedirectingFetcher {
      override suspend fun get(url: String, etag: String?) = get(url, etag, Long.MAX_VALUE) { false }
      override suspend fun get(url: String, etag: String?, maxBytes: Long, allowRedirect: (String) -> Boolean): FetchResult {
        fetched += url
        return FetchResult.Ok(File(samples, "catalog/designs/banner-bold/assets/hero.png").readBytes(), null)
      }
    }
    val web = web(
      DocumentAdmission(JavaCrypto, emptyList(), agentBytes = AgentBytesPolicy(setOf("shop/"), maxBytes = 1 shl 20)),
      ImageResolver(repository, fetcher, JavaCrypto, directHosts = setOf("img.example")),
    )
    val p = props("""{"name":"Socks","template":{"inline":"${Base64.encode(withImage)}"}}""")
    assertIs<LiveryA2uiWeb.Rendered.Waiting>(web.render("ShopQuantity", p), "the web player draws no image from a URL, so it is fetched and written in")
    assertTrue(web.fetchImages())
    val ready = assertIs<LiveryA2uiWeb.Rendered.Ready>(web.render("ShopQuantity", p))
    assertTrue(TemplateInstantiator.bitmapUrls(ready.document).none { it.startsWith("https://") }, "the image is in the document now")
  }
  @Test fun wiringOneActionDoesNotEnableOthersWithTheSameKind() = runTest {
    val manifest = built.manifest.copy(contracts = built.manifest.contracts.map { c ->
      if (c.ref != "promo/banner@1") c else c.copy(actions = c.actions + ("other" to c.actions.getValue("cta")))
    })
    val repo = TemplateRepository(
      StaticSource("https://catalog.invalid/"), Fetcher { _, _ -> FetchResult.Failed(503, "offline") },
      JavaCrypto, TrustPolicy.AllowUnsigned, MemoryBlobStore(), MemoryKeyValueStore(), clock, listOf(caps),
      TemplateRepository.Bundled(manifest) { sha -> built.files.entries.firstOrNull { it.key.contains(sha) }?.value },
    )
    val web = LiveryA2uiWeb(repo, SlotPlanner(repo, { events += it }, clock), caps, { events += it }, clock)
    val r = assertIs<LiveryA2uiWeb.Rendered.Ready>(web.render("PromoBanner", promo))
    assertIs<LiveryA2uiWeb.Routed.Agent>(web.action(r.id, "cta", "string", """{"route":"/premium"}"""))
    assertIs<LiveryA2uiWeb.Routed.Dropped>(web.action(r.id, "other", "string", """{"route":"/premium"}"""))
  }

}
