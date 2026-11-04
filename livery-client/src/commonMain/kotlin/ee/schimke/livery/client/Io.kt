package ee.schimke.livery.client

/** HTTP (or file, or asset) GET with conditional revalidation. Platforms bring their own. */
fun interface Fetcher {
  suspend fun get(url: String, etag: String?): FetchResult
}

sealed interface FetchResult {
  data class Ok(val bytes: ByteArray, val etag: String?, val retryAfterSeconds: Long? = null) : FetchResult
  data object NotModified : FetchResult
  data class Failed(val status: Int?, val message: String, val retryAfterSeconds: Long? = null) : FetchResult
}

/** Hashes and signature checks; JVM/Android use the JDK, iOS/web bring their own. */
interface Crypto {
  fun sha256Hex(bytes: ByteArray): String

  /** Verify an Ed25519 [signature] over [message] with the raw 32-byte [publicKey]. */
  fun verifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}

/** Content-addressed bytes: blobs and previews. Keys are sha256 hex. */
interface BlobStore {
  suspend fun get(sha256: String): ByteArray?
  suspend fun put(sha256: String, bytes: ByteArray)
  suspend fun delete(sha256: String)
  suspend fun keys(): Set<String>
}

/** Small named documents: the last good manifest, its metadata, installed designs, caps. */
interface KeyValueStore {
  suspend fun get(key: String): String?
  suspend fun put(key: String, value: String)
  suspend fun remove(key: String)
}

class MemoryBlobStore : BlobStore {
  private val map = LinkedHashMap<String, ByteArray>()
  override suspend fun get(sha256: String) = map[sha256]
  override suspend fun put(sha256: String, bytes: ByteArray) { map[sha256] = bytes }
  override suspend fun delete(sha256: String) { map.remove(sha256) }
  override suspend fun keys(): Set<String> = map.keys.toSet()
}

class MemoryKeyValueStore : KeyValueStore {
  private val map = LinkedHashMap<String, String>()
  override suspend fun get(key: String) = map[key]
  override suspend fun put(key: String, value: String) { map[key] = value }
  override suspend fun remove(key: String) { map.remove(key) }
}

fun interface Clock {
  fun nowEpochMs(): Long
}
