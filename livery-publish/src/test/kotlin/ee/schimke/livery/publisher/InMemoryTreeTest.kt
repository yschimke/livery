package ee.schimke.livery.publisher

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/** The common catalog code reads a tree in an in-memory file system exactly as it reads one on disk. */
class InMemoryTreeTest {
  private val sample = File(requireNotNull(System.getProperty("livery.samples")) { "run through Gradle: gradle/sample-catalogs.gradle.kts" }).canonicalFile

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

  /** Linked designs and assets are read as their targets, as java.io.File did before Okio. */
  @Test fun followsSymbolicLinks() {
    val linked = java.nio.file.Files.createTempDirectory("livery-links").toFile()
    sample.listFiles()!!.forEach { f ->
      if (f.name == "designs") {
        val designs = File(linked, "designs").apply { mkdirs() }
        f.listFiles()!!.forEach { d -> java.nio.file.Files.createSymbolicLink(File(designs, d.name).toPath(), d.toPath()) }
      } else java.nio.file.Files.createSymbolicLink(File(linked, f.name).toPath(), f.toPath())
    }
    try {
      val direct = CatalogBuilder(now = { 0 }).build(sample, Layout.Static, serial = 1)
      val viaLinks = CatalogBuilder(now = { 0 }).build(linked, Layout.Static, serial = 1)
      assertEquals(direct.manifestBytes.decodeToString(), viaLinks.manifestBytes.decodeToString())
    } finally {
      // Never File.deleteRecursively here: it follows the links and deletes the sample itself.
      // Files.walk doesn't follow links, so only the links and their folders go.
      java.nio.file.Files.walk(linked.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(java.nio.file.Files::delete) }
    }
  }

  @Test fun compareAndKeepInMemory() {
    val fs = FakeFileSystem()
    val tree = copy(fs, "/tree")
    val base = copy(fs, "/base")
    assertTrue(CompatibilityCheck.compare(fs, tree, base, "main").ok)
    assertEquals(emptyList(), KeptExamples.keep(fs, tree, base))
  }
}
