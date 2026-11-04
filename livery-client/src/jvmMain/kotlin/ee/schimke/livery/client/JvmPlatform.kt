package ee.schimke.livery.client

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext

/** JDK crypto: SHA-256 and Ed25519 (JDK 15+, Android API 33+; older Android brings BouncyCastle/Tink). */
object JvmCrypto : Crypto {
  override fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  override fun verifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = runCatching {
    val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(ED25519_X509_PREFIX + publicKey))
    Signature.getInstance("Ed25519").run { initVerify(key); update(message); verify(signature) }
  }.getOrDefault(false)

  /** DER prefix that wraps a raw 32-byte Ed25519 key as SubjectPublicKeyInfo. */
  val ED25519_X509_PREFIX: ByteArray = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
}

/** java.net.http fetcher; also reads `file:` URLs and `asset://` from [assetRoot]. */
class JvmFetcher(
  private val client: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build(),
  private val assetRoot: File? = null,
) : Fetcher {
  override suspend fun get(url: String, etag: String?): FetchResult = withContext(Dispatchers.IO) {
    when {
      url.startsWith("file:") -> readFile(File(URI(url)))
      url.startsWith("asset://") -> assetRoot?.let { readFile(File(it, url.removePrefix("asset://"))) } ?: FetchResult.Failed(null, "no asset root")
      else -> runCatching {
        val req = HttpRequest.newBuilder(URI(url)).GET().timeout(Duration.ofSeconds(30)).apply { etag?.let { header("If-None-Match", it) } }.build()
        val res = client.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray()).await()
        val retry = res.headers().firstValue("Retry-After").orElse(null)?.toLongOrNull()
        when (res.statusCode()) {
          200 -> FetchResult.Ok(res.body(), res.headers().firstValue("ETag").orElse(null))
          304 -> FetchResult.NotModified
          else -> FetchResult.Failed(res.statusCode(), "HTTP ${res.statusCode()}", retry)
        }
      }.getOrElse { FetchResult.Failed(null, it.message ?: it::class.simpleName.orEmpty()) }
    }
  }

  private fun readFile(f: File): FetchResult = if (f.isFile) FetchResult.Ok(f.readBytes(), null) else FetchResult.Failed(404, "missing $f")
}

/** Files named by hash, written atomically (tmp + rename). */
class FileBlobStore(private val dir: File) : BlobStore {
  init { dir.mkdirs() }
  private fun f(sha: String) = File(dir, "$sha.rc").also { require(sha.matches(Regex("[0-9a-f]{64}"))) { "not a sha256: $sha" } }
  override suspend fun get(sha256: String): ByteArray? = withContext(Dispatchers.IO) { f(sha256).takeIf { it.isFile }?.readBytes() }
  override suspend fun put(sha256: String, bytes: ByteArray) = withContext(Dispatchers.IO) { atomicWrite(f(sha256), bytes) }
  override suspend fun delete(sha256: String) { withContext(Dispatchers.IO) { f(sha256).delete() } }
  override suspend fun keys(): Set<String> = withContext(Dispatchers.IO) { dir.listFiles().orEmpty().filter { it.name.endsWith(".rc") }.map { it.name.removeSuffix(".rc") }.toSet() }
}

class FileKeyValueStore(private val dir: File) : KeyValueStore {
  init { dir.mkdirs() }
  /** A collision-free file name: the SHA-256 of the whole key (keys hold URLs and arbitrary ids). */
  private fun f(key: String) = File(dir, JvmCrypto.sha256Hex(key.encodeToByteArray()))
  override suspend fun get(key: String): String? = withContext(Dispatchers.IO) { f(key).takeIf { it.isFile }?.readText() }
  override suspend fun put(key: String, value: String) = withContext(Dispatchers.IO) { atomicWrite(f(key), value.encodeToByteArray()) }
  override suspend fun remove(key: String) { withContext(Dispatchers.IO) { f(key).delete() } }
}

internal fun atomicWrite(target: File, bytes: ByteArray) {
  val tmp = File(target.parentFile, ".${target.name}.tmp")
  tmp.writeBytes(bytes)
  if (!tmp.renameTo(target)) { target.delete(); check(tmp.renameTo(target)) { "rename failed: $target" } }
}
