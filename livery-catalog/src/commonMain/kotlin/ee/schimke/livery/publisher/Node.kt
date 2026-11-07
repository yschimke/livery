package ee.schimke.livery.publisher

import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path

/**
 * A file or directory of a catalog source tree on an Okio [FileSystem]: the system's at the JVM
 * edge, a `FakeFileSystem` in memory (a browser, an IDE, an agent, a one-document preview).
 */
internal class Node(val fs: FileSystem, val path: Path) {
  val name: String get() = path.name
  val isFile: Boolean get() = fs.metadataOrNull(path)?.isRegularFile == true
  val isDirectory: Boolean get() = fs.metadataOrNull(path)?.isDirectory == true
  val extension: String get() = name.substringAfterLast('.', "")
  val nameWithoutExtension: String get() = name.substringBeforeLast(".")

  /** The child [name] (or `/`-separated path) of this directory, which may not exist. */
  fun resolve(name: String): Node = Node(fs, path / name)

  /** The entries of this directory, or null when it isn't one. */
  fun listFiles(): List<Node>? = fs.listOrNull(path)?.map { Node(fs, it) }

  fun readBytes(): ByteArray = fs.read(path) { readByteArray() }

  fun readText(): String = fs.read(path) { readUtf8() }

  fun length(): Long = fs.metadata(path).size ?: readBytes().size.toLong()

  /** Writes [bytes] here, creating parent directories. */
  fun writeBytes(bytes: ByteArray) {
    path.parent?.let { fs.createDirectories(it) }
    fs.write(path) { write(bytes) }
  }

  fun writeText(text: String) = writeBytes(text.encodeToByteArray())

  fun delete() = fs.delete(path, mustExist = false)

  override fun toString(): String = path.toString()
}

/** SHA-256 as lowercase hex: Okio's, on every platform. */
internal fun sha256Hex(bytes: ByteArray): String = bytes.toByteString().sha256().hex()
