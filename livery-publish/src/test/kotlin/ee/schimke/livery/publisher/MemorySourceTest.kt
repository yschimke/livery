package ee.schimke.livery.publisher

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The common catalog code reads a tree in memory exactly as it reads the same tree on disk. */
class MemorySourceTest {
  private val sample = File(System.getProperty("user.dir")).resolve("../samples/catalog").canonicalFile

  private fun inMemory(root: File) = MemorySource(
    root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes() }.toMutableMap(),
  )

  @Test fun sameCatalogFromMemoryAndDisk() {
    val disk = CatalogBuilder(now = { 0 }).build(sample, Layout.Static, serial = 1)
    val memory = CatalogBuilder(now = { 0 }).build(inMemory(sample), Layout.Static, serial = 1)
    assertEquals(disk.manifestBytes.decodeToString(), memory.manifestBytes.decodeToString())
    assertEquals(disk.files.keys, memory.files.keys)
    assertTrue(disk.files.all { (k, v) -> v.contentEquals(memory.files.getValue(k)) })
  }

  @Test fun compareAndKeepInMemory() {
    val tree = inMemory(sample)
    assertTrue(CompatibilityCheck.compare(tree, inMemory(sample), "main").ok)
    assertEquals(emptyList(), KeptExamples.keep(tree, inMemory(sample)))
  }
}
