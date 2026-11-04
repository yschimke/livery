package ee.schimke.livery.client

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class FileKeyValueStoreTest {
  @Test fun keysThatDifferOnlyInPunctuationDoNotCollide() = runBlocking {
    val store = FileKeyValueStore(Files.createTempDirectory("kv").toFile())
    store.put("livery/https://cdn.example/a/b/manifest", "one")
    store.put("livery/https://cdn.example/a_b/manifest", "two")
    assertEquals("one", store.get("livery/https://cdn.example/a/b/manifest"))
    assertEquals("two", store.get("livery/https://cdn.example/a_b/manifest"))
  }
}

class AtomicWriteTest {
  @Test fun concurrentWritesOfOneBlobLeaveItIntact() = runBlocking {
    val dir = Files.createTempDirectory("blobs").toFile()
    val store = FileBlobStore(dir)
    val bytes = ByteArray(256 * 1024) { it.toByte() }
    val sha = JavaCrypto.sha256HexBlocking(bytes)
    kotlinx.coroutines.coroutineScope {
      repeat(16) { launch(kotlinx.coroutines.Dispatchers.IO) { store.put(sha, bytes) } }
    }
    assertEquals(bytes.toList(), store.get(sha)?.toList())
    assertEquals(listOf("$sha.rc"), dir.list()!!.toList(), "no temporary files left behind")
  }
}
