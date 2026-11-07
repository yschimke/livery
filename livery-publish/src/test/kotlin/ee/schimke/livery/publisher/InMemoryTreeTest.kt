package ee.schimke.livery.publisher

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/** The common catalog code reads a tree in an in-memory file system exactly as it reads one on disk. */
class InMemoryTreeTest {
  private val sample = File(System.getProperty("user.dir")).resolve("../samples/catalog").canonicalFile

  /** [sample] copied into [fs] at [at]. */
  private fun copy(fs: FakeFileSystem, at: String) = at.toPath().also { root ->
    sample.walkTopDown().filter { it.isFile }.forEach { f ->
      val p = root / f.relativeTo(sample).invariantSeparatorsPath
      fs.createDirectories(p.parent!!)
      fs.write(p) { write(f.readBytes()) }
    }
  }

  @Test fun sameCatalogFromMemoryAndDisk() {
    val fs = FakeFileSystem()
    val disk = CatalogBuilder(now = { 0 }).build(sample, Layout.Static, serial = 1)
    val memory = CatalogBuilder(now = { 0 }).build(fs, copy(fs, "/tree"), Layout.Static, serial = 1)
    assertEquals(disk.manifestBytes.decodeToString(), memory.manifestBytes.decodeToString())
    assertEquals(disk.files.keys, memory.files.keys)
    assertTrue(disk.files.all { (k, v) -> v.contentEquals(memory.files.getValue(k)) })
  }

  @Test fun compareAndKeepInMemory() {
    val fs = FakeFileSystem()
    val tree = copy(fs, "/tree")
    val base = copy(fs, "/base")
    assertTrue(CompatibilityCheck.compare(fs, tree, base, "main").ok)
    assertEquals(emptyList(), KeptExamples.keep(fs, tree, base))
  }
}
