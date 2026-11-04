package ee.schimke.livery.client

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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
