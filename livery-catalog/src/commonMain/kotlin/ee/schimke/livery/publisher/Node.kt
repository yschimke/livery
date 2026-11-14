package ee.schimke.livery.publisher

import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import okio.FileMetadata
import okio.FileSystem
import okio.Path

/**
 * A file or directory of a catalog source tree on an Okio [FileSystem]: the system's at the JVM
 * edge, a `FakeFileSystem` in memory (a browser, an IDE, an agent, a one-document preview).
 */
internal class Node(val fs: FileSystem, val path: Path) {
  val name: String get() = path.name
  val isFile: Boolean get() = fs.targetMetadataOrNull(path)?.isRegularFile == true
  val isDirectory: Boolean get() = fs.targetMetadataOrNull(path)?.isDirectory == true
  val extension: String get() = name.substringAfterLast('.', "")
  val nameWithoutExtension: String get() = name.substringBeforeLast(".")

  /** The child [name] (or `/`-separated path) of this directory, which may not exist. */
  fun resolve(name: String): Node = Node(fs, path / name)

  /** The entries of this directory, or null when it isn't one. */
  fun listFiles(): List<Node>? = fs.listOrNull(path)?.map { Node(fs, it) }

  fun readBytes(): ByteString = fs.read(path) { readByteString() }

  fun readText(): String = fs.read(path) { readUtf8() }

  fun length(): Long = fs.targetMetadataOrNull(path)?.size ?: readBytes().size.toLong()

  /** Writes [bytes] here, creating parent directories. */
  fun writeBytes(bytes: ByteString) {
    path.parent?.let { fs.createDirectories(it) }
    fs.write(path) { write(bytes) }
  }

  fun writeText(text: String) = writeBytes(text.encodeUtf8())

  fun delete() = fs.delete(path, mustExist = false)

  override fun toString(): String = path.toString()
}

/** SHA-256 as lowercase hex: Okio's, on every platform. */
internal fun sha256Hex(bytes: ByteString): String = bytes.sha256().hex()

/**
 * What [path] is, following a symbolic link to its target as java.io.File does (Okio's own
 * metadata describes the link); null when it's absent or the link dangles.
 */
fun FileSystem.targetMetadataOrNull(path: Path): FileMetadata? {
  val m = metadataOrNull(path) ?: return null
  if (m.symlinkTarget == null) return m
  return runCatching { metadataOrNull(canonicalize(path)) }.getOrNull()
}
