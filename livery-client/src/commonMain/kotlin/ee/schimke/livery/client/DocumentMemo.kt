package ee.schimke.livery.client

import okio.ByteString
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A few templates' bytes, in memory, by the hash that names them. A template is content-addressed
 * ([TemplateEntry.sha256]), so a copy never goes stale and never needs invalidating, and one copy serves every
 * card of it: a slot that plans again reads nothing from the disk or the APK, and gets the same array back.
 *
 * Copy-on-write behind an atomic reference, so a read takes no lock while a load writes from another thread;
 * entries are few and written rarely.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class DocumentMemo(private val max: Int) {
  private val ref = AtomicReference<Map<String, ByteString>>(emptyMap())

  operator fun get(sha256: String): ByteString? = ref.load()[sha256]

  /** Keeps [bytes] and returns the copy now held: the one already there if another load got in first. */
  fun put(sha256: String, bytes: ByteString): ByteString {
    while (true) {
      val current = ref.load()
      current[sha256]?.let { return it }
      val next = LinkedHashMap<String, ByteString>(current.size + 1)
      next.putAll(current)
      next[sha256] = bytes
      while (next.size > max) next.remove(next.keys.first())
      if (ref.compareAndSet(current, next)) return bytes
    }
  }
}
