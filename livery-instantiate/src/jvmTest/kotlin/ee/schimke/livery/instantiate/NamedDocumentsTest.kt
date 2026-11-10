package ee.schimke.livery.instantiate

import ee.schimke.livery.client.*
import ee.schimke.livery.protocol.*
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class NamedDocumentsTest {
  private val bytes = File(requireNotNull(System.getProperty("livery.samples")) { "run through Gradle: gradle/sample-catalogs.gradle.kts" }, "designs/tile-default/in-app.rc").readBytes()
  private val contract = Json { ignoreUnknownKeys = true }.decodeFromString(Contract.serializer(), File(requireNotNull(System.getProperty("livery.samples")) { "run through Gradle: gradle/sample-catalogs.gradle.kts" }, "contracts/status-tile.json").readText())
  private val caps = PlayerCapabilities(Surfaces.IN_APP, 7, setOf("androidx"))
  private val query = TemplateQuery(contract.ref, caps)
  private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
  private val key = TrustedKey("test", pair.public.encoded.takeLast(32).toByteArray())
  private val repository = TemplateRepository(StaticSource("https://catalog.example/"), Fetcher { _, _ -> error("no catalog network") }, JavaCrypto,
    TrustPolicy.AllowUnsigned, MemoryBlobStore(), MemoryKeyValueStore(), Clock { 0 }, listOf(caps))

  private suspend fun signature() = ManifestSignature(keyId = "test", signature = Base64.encode(Signature.getInstance("Ed25519").run {
    initSign(pair.private); update(TemplateSignatures.message(contract.ref, JavaCrypto.sha256Hex(bytes))); sign()
  }))

  @Test fun signedFetchChecksBothHostsLimitsAndHash() = runBlocking {
    var calls = 0
    val fetcher = object : RedirectingFetcher {
      override suspend fun get(url: String, etag: String?): FetchResult = error("must use bounded redirect-aware fetch")
      override suspend fun get(url: String, etag: String?, maxBytes: Long, allowRedirect: (String) -> Boolean): FetchResult {
        calls++
        assertEquals(bytes.size.toLong(), maxBytes)
        assertTrue(allowRedirect("https://catalog.example/next"))
        assertFalse(allowRedirect("https://other.example/next"))
        assertFalse(allowRedirect("http://catalog.example/next"))
        return FetchResult.Ok(bytes, null)
      }
    }
    val resolver = NamedDocuments(repository, DocumentAdmission(JavaCrypto, listOf(key), maxSignedBytes = bytes.size), fetcher)
    val sha = JavaCrypto.sha256Hex(bytes)
    assertNull(resolver.load(sha, "https://other.example/x", signature(), contract, query))
    assertNull(resolver.load(sha, "https://catalog.example/x", null, contract, query))
    assertNull(resolver.load(sha, "https://catalog.example/x", signature(), contract, query, network = false))
    assertEquals(0, calls)
    assertEquals(sha, resolver.load(sha, "https://catalog.example/x", signature(), contract, query)?.entry?.sha256)
    assertEquals("https://catalog.example/blobs/$sha.rc", repository.documentUrl(sha))
    assertEquals(sha, resolver.load(sha, null, signature(), contract, query)?.entry?.sha256)
    assertNull(resolver.load("0".repeat(64), "https://catalog.example/x", signature(), contract, query))
    assertNull(resolver.load(sha, "https://catalog.example/x", signature().copy(signature = "bad"), contract, query))
  }

  @Test fun heightUsesTargetMinimumThenAuthoredHeightAndToleratesInvalidBytes() {
    val entry = TemplateEntry("a".repeat(64), bytes.size.toLong(), "tile", contract.ref, Target(Surfaces.IN_APP, 7))
    val loaded = LoadedTemplate(entry, null, bytes, LoadedTemplate.Origin.Bundled, contract)
    val plan = RenderPlan(loaded, contract, Bindings.EMPTY)
    assertEquals(TemplateInstantiator.authoredHeightDp(bytes), plan.templateHeightDp())
    assertEquals(123, plan.copy(template = loaded.copy(entry = entry.copy(target = entry.target.copy(size = SizeRange(minHeightDp = 123))))).templateHeightDp())
    assertNull(plan.copy(template = loaded.copy(bytes = byteArrayOf())).templateHeightDp())
  }
}
