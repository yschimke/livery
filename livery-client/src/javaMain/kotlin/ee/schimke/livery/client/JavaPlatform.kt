package ee.schimke.livery.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toOkioPath

/**
 * JDK Ed25519 (SHA-256 is Okio's, from [Crypto]). Ed25519 needs JDK 15+ on the JVM and API 33+ on Android;
 * an app supporting older Android passes a [Crypto] backed by Tink or BouncyCastle instead.
 */
object JavaCrypto : Crypto {
  /** [sha256Hex] for callers that are not suspending (build tools, file names). */
  fun sha256HexBlocking(bytes: ByteString): String = bytes.sha256().hex()

  override suspend fun verifyEd25519(publicKey: ByteString, message: ByteString, signature: ByteString): Boolean = runCatching {
    val spec = Buffer().write(ED25519_X509_PREFIX).write(publicKey).readByteArray()
    val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spec))
    Signature.getInstance("Ed25519").run { initVerify(key); update(message.toByteArray()); verify(signature.toByteArray()) }
  }.getOrDefault(false)

  /** DER prefix that wraps a raw 32-byte Ed25519 key as SubjectPublicKeyInfo. */
  val ED25519_X509_PREFIX: ByteString = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00).toByteString()
}

actual fun defaultHttpClient(): HttpClient = HttpClient(OkHttp) { liveryDefaults() }

/**
 * Local catalogs: `file:` URLs, and `asset://<path>` from [assets] (on Android,
 * `{ context.assets.open(it).source().buffer().readByteString() }`). Pass it as [KtorFetcher]'s `local`.
 */
class LocalFetcher(
  private val assets: ((path: String) -> ByteString?)? = null,
  private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : Fetcher {
  override suspend fun get(url: String, etag: String?): FetchResult = withContext(Dispatchers.IO) {
    when {
      url.startsWith("file:") -> File(URI(url)).toOkioPath().let { path ->
        if (fileSystem.metadataOrNull(path)?.isRegularFile == true) FetchResult.Ok(fileSystem.read(path) { readByteString() }, null) else FetchResult.Failed(404, "missing $path")
      }
      url.startsWith("asset://") ->
        runCatching { assets?.invoke(url.removePrefix("asset://")) }.getOrNull()?.let { FetchResult.Ok(it, null) }
          ?: FetchResult.Failed(404, "no asset $url")
      else -> FetchResult.Failed(null, "not a local URL: $url")
    }
  }
}

/** Files named by hash, written atomically (tmp + rename). [fileSystem] is Okio's, so a test can use a fake one. */
class FileBlobStore(private val fileSystem: FileSystem, private val dir: Path) : BlobStore {
  constructor(dir: File) : this(FileSystem.SYSTEM, dir.toOkioPath())

  init { fileSystem.createDirectories(dir) }
  private fun f(sha: String): Path {
    require(sha.matches(Regex("[0-9a-f]{64}"))) { "not a sha256: $sha" }
    return dir / "$sha.rc"
  }
  override suspend fun get(sha256: String): ByteString? = withContext(Dispatchers.IO) {
    f(sha256).takeIf { fileSystem.metadataOrNull(it)?.isRegularFile == true }?.let { fileSystem.read(it) { readByteString() } }
  }
  override suspend fun put(sha256: String, bytes: ByteString) = withContext(Dispatchers.IO) { atomicWrite(fileSystem, f(sha256), bytes) }
  override suspend fun delete(sha256: String) { withContext(Dispatchers.IO) { fileSystem.delete(f(sha256), mustExist = false) } }
  override suspend fun keys(): Set<String> = withContext(Dispatchers.IO) {
    fileSystem.list(dir).map { it.name }.filter { it.endsWith(".rc") }.map { it.removeSuffix(".rc") }.toSet()
  }
}

class FileKeyValueStore(private val fileSystem: FileSystem, private val dir: Path) : KeyValueStore {
  constructor(dir: File) : this(FileSystem.SYSTEM, dir.toOkioPath())

  init { fileSystem.createDirectories(dir) }
  /** A collision-free file name: the SHA-256 of the whole key (keys hold URLs and arbitrary ids). */
  private fun f(key: String): Path = dir / key.encodeUtf8().sha256().hex()
  override suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
    f(key).takeIf { fileSystem.metadataOrNull(it)?.isRegularFile == true }?.let { fileSystem.read(it) { readUtf8() } }
  }
  override suspend fun put(key: String, value: String) = withContext(Dispatchers.IO) { atomicWrite(fileSystem, f(key), value.encodeUtf8()) }
  override suspend fun remove(key: String) { withContext(Dispatchers.IO) { fileSystem.delete(f(key), mustExist = false) } }
}

/**
 * Writes through a temporary file of its own, then moves it over [target] atomically, so concurrent
 * writers (a download and a prefetch of the same blob) never share a temporary file, and a failure
 * only ever removes its own temporary file, never another writer's result.
 */
internal fun atomicWrite(fileSystem: FileSystem, target: Path, bytes: ByteString) {
  val tmp = target.parent!! / ".${target.name}.${Random.nextLong().toULong().toString(16)}.tmp"
  try {
    fileSystem.write(tmp) { write(bytes) }
    try {
      fileSystem.atomicMove(tmp, target)
    } catch (_: IOException) {
      // A file system without an atomic move (some network mounts): a copy still replaces the target.
      fileSystem.copy(tmp, target)
    }
  } finally {
    fileSystem.delete(tmp, mustExist = false)
  }
}
