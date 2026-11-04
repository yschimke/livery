package ee.schimke.livery.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * JDK crypto: SHA-256 and Ed25519. Ed25519 needs JDK 15+ on the JVM and API 33+ on Android; an app
 * supporting older Android passes a [Crypto] backed by Tink or BouncyCastle instead.
 */
object JavaCrypto : Crypto {
  override suspend fun sha256Hex(bytes: ByteArray): String = sha256HexBlocking(bytes)

  /** [sha256Hex] for callers that are not suspending (build tools, file names). */
  fun sha256HexBlocking(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  override suspend fun verifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = runCatching {
    val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(ED25519_X509_PREFIX + publicKey))
    Signature.getInstance("Ed25519").run { initVerify(key); update(message); verify(signature) }
  }.getOrDefault(false)

  /** DER prefix that wraps a raw 32-byte Ed25519 key as SubjectPublicKeyInfo. */
  val ED25519_X509_PREFIX: ByteArray = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
}

actual fun defaultHttpClient(): HttpClient = HttpClient(OkHttp) { liveryDefaults() }

/**
 * Local catalogs: `file:` URLs, and `asset://<path>` from [assets] (on Android,
 * `{ context.assets.open(it).readBytes() }`). Pass it as [KtorFetcher]'s `local`.
 */
class LocalFetcher(private val assets: ((path: String) -> ByteArray?)? = null) : Fetcher {
  override suspend fun get(url: String, etag: String?): FetchResult = withContext(Dispatchers.IO) {
    when {
      url.startsWith("file:") -> File(URI(url)).let { if (it.isFile) FetchResult.Ok(it.readBytes(), null) else FetchResult.Failed(404, "missing $it") }
      url.startsWith("asset://") ->
        runCatching { assets?.invoke(url.removePrefix("asset://")) }.getOrNull()?.let { FetchResult.Ok(it, null) }
          ?: FetchResult.Failed(404, "no asset $url")
      else -> FetchResult.Failed(null, "not a local URL: $url")
    }
  }
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
  private fun f(key: String) = File(dir, JavaCrypto.sha256HexBlocking(key.encodeToByteArray()))
  override suspend fun get(key: String): String? = withContext(Dispatchers.IO) { f(key).takeIf { it.isFile }?.readText() }
  override suspend fun put(key: String, value: String) = withContext(Dispatchers.IO) { atomicWrite(f(key), value.encodeToByteArray()) }
  override suspend fun remove(key: String) { withContext(Dispatchers.IO) { f(key).delete() } }
}

/**
 * Writes through a temporary file of its own, then moves it over [target] atomically, so concurrent
 * writers (a download and a prefetch of the same blob) never share a temporary file, and a failure
 * only ever removes its own temporary file, never another writer's result.
 */
internal fun atomicWrite(target: File, bytes: ByteArray) {
  val tmp = File.createTempFile(".${target.name}.", ".tmp", target.parentFile)
  try {
    tmp.writeBytes(bytes)
    try {
      Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
  } finally {
    tmp.delete()
  }
}
