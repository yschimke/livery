package ee.schimke.livery.publisher

import ee.schimke.livery.client.FileBlobStore
import ee.schimke.livery.client.FileKeyValueStore
import ee.schimke.livery.client.JvmCrypto
import ee.schimke.livery.client.JvmFetcher
import ee.schimke.livery.client.PlanResult
import ee.schimke.livery.client.RefreshResult
import ee.schimke.livery.client.SlotPlanner
import ee.schimke.livery.client.StaticSource
import ee.schimke.livery.client.TemplateQuery
import ee.schimke.livery.client.TemplateRepository
import ee.schimke.livery.client.TrustPolicy
import ee.schimke.livery.client.TrustedKey
import ee.schimke.livery.client.DecisionContext
import ee.schimke.livery.client.LocalRulesDecider
import ee.schimke.livery.client.MemoryFrequencyStore
import ee.schimke.livery.protocol.Manifest
import ee.schimke.livery.protocol.ManifestSignature
import ee.schimke.livery.protocol.PlayerCapabilities
import ee.schimke.livery.protocol.Surfaces
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/** Sample source tree → signed static catalog on disk → the real JVM client reading it via file: URLs. */
class PublishEndToEndTest {
  private val sample = File(System.getProperty("user.dir")).resolve("../samples/catalog").canonicalFile

  @Test fun buildSignServeResolve() = runBlocking {
    val out = Files.createTempDirectory("catalog").toFile()
    val keys = Signing.generate()
    val built = CatalogBuilder(now = { 1_795_000_000_000 }).build(sample, Layout.Static, serial = 7)
    built.files.forEach { (p, b) -> File(out, p).apply { parentFile.mkdirs() }.writeBytes(b) }
    File(out, Manifest.FILE).writeBytes(built.manifestBytes)
    File(out, Manifest.SIGNATURE_FILE).writeText(Json.encodeToString(ManifestSignature.serializer(), Signing.sign(built.manifestBytes, keys.privatePkcs8Base64, "sample-1")))

    val cache = Files.createTempDirectory("cache").toFile()
    val inApp = PlayerCapabilities(Surfaces.IN_APP, 7, setOf("androidx"))
    val launcher = PlayerCapabilities(Surfaces.LAUNCHER_WIDGET, 7, setOf("widgets"), livePush = false)
    val repo = TemplateRepository(
      StaticSource(out.toURI().toString().trimEnd('/')), JvmFetcher(), JvmCrypto,
      TrustPolicy.Signed(listOf(TrustedKey("sample-1", Base64.getDecoder().decode(keys.publicRawBase64)))),
      FileBlobStore(File(cache, "blobs")), FileKeyValueStore(File(cache, "kv")), { 1_795_000_000_000 }, listOf(inApp, launcher),
    )
    assertEquals(RefreshResult.Updated(7), repo.refresh())

    // Live content: the app's own data.
    val events = mutableListOf<Any>()
    val planner = SlotPlanner(repo, { events += it }, { 0 })
    val tile = planner.forAppData(TemplateQuery("live/status-tile@1", inApp), Json.parseToJsonElement("""{"name":"Kitchen","state":"On","is_on":true}""").jsonObject())
    assertEquals("tile-default", assertIs<PlanResult.Ready>(tile, "$events").plan.template.entry.designId)

    // Launcher widget for a 200×100 dp cell.
    assertEquals(Surfaces.LAUNCHER_WIDGET, repo.resolve(TemplateQuery("live/status-tile@1", launcher, 200, 100))?.entry?.target?.surface)

    // Growth: local rule → decision → banner with its data.
    val ctx = DecisionContext(1_795_000_000_000, "android", "en-GB", 1, "user-42")
    val decider = LocalRulesDecider({ repo.manifest() }, MemoryFrequencyStore())
    val decision = generateSequence(0) { it + 1 }.map { ctx.copy(bucketingId = "user-$it") }.firstNotNullOf { decider.decide("home.top", it) }
    assertIs<PlanResult.Ready>(planner.forDecision(TemplateQuery("promo/banner@1", inApp), decision), "$events")

    // Install a skin that isn't shipped by default.
    assertTrue(repo.install("tile-retro"))
  }

  @Test fun refusesBadCatalogs() {
    val src = Files.createTempDirectory("bad").toFile()
    sample.copyRecursively(src)
    File(src, "placements/spring-trial.json").writeText(File(src, "placements/spring-trial.json").readText().replace("\"Upgrade\"", "\"This CTA is far longer than eighteen\""))
    File(src, "designs/banner-bold/in-app.json").writeText("""{ "target": { "surface": "in-app", "rcApiLevel": 7 }, "reads": ["price"] }""")
    File(src, "designs/article-card/web.json").writeText("""{ "target": { "surface": "web", "rcApiLevel": 7 }, "reads": ["tags.count", "tags.0.label", "tags.0.typo", "tags.3.label", "tags.-1.label", "tags.01.label"] }""")
    val e = assertFailsWith<CatalogProblems> { CatalogBuilder().build(src, Layout.Static) }
    assertTrue(e.problems.any { "spring-trial" in it && "cta" in it }, e.message)
    assertTrue(e.problems.any { "price" in it }, e.message)
    val listProblem = e.problems.single { "article-card/web.rc" in it }
    listOf("tags.0.typo", "tags.3.label", "tags.-1.label", "tags.01.label").forEach { assertTrue(it in listProblem, listProblem) }
    assertTrue("tags.0.label," !in listProblem && "tags.count" !in listProblem, listProblem)
  }

  @Test fun githubLayoutIsFlatAndAbsolute() {
    val built = CatalogBuilder().build(sample, Layout.GitHubRelease("acme", "app-templates", "catalog-7"))
    assertTrue(built.files.keys.none { '/' in it })
    assertEquals("https://github.com/acme/app-templates/releases/download/catalog-7/", built.manifest.blobBase)
  }
}

private fun kotlinx.serialization.json.JsonElement.jsonObject() = this as kotlinx.serialization.json.JsonObject
