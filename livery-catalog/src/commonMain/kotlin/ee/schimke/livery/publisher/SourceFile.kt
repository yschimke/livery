package ee.schimke.livery.publisher

/**
 * A file or directory of a catalog source tree, read without java.io: a JVM `File` (livery-publish's
 * `FileSource`), an in-memory tree ([MemorySource]), or anything else that can list and read. Only
 * what building and checking a catalog needs.
 */
interface SourceFile {
  val name: String
  val isFile: Boolean
  val isDirectory: Boolean

  /** The child [name] of this directory, which may not exist. */
  fun resolve(name: String): SourceFile

  /** The entries of this directory, or null when it isn't one. */
  fun listFiles(): List<SourceFile>?

  fun readBytes(): ByteArray

  fun readText(): String = readBytes().decodeToString()

  fun length(): Long = readBytes().size.toLong()

  /** Writes [bytes] here, creating parent directories: for the commands that write into a tree. */
  fun writeBytes(bytes: ByteArray): Unit = throw UnsupportedOperationException("$name: this source is read-only")

  fun writeText(text: String) = writeBytes(text.encodeToByteArray())

  /** Removes this file, if it exists: for undoing a write. */
  fun delete(): Unit = throw UnsupportedOperationException("$name: this source is read-only")
}

val SourceFile.extension: String get() = name.substringAfterLast('.', "")

val SourceFile.nameWithoutExtension: String get() = name.substringBeforeLast(".")

/**
 * A source tree in memory, keyed by `/`-separated paths relative to its root (`catalog.json`,
 * `designs/a/in-app.rc`); directories are implied by the paths. Writes go into [files].
 */
class MemorySource(val files: MutableMap<String, ByteArray> = linkedMapOf(), private val path: String = "") : SourceFile {
  override val name: String get() = path.substringAfterLast('/')
  override val isFile: Boolean get() = path in files
  override val isDirectory: Boolean get() = path.isEmpty() || files.keys.any { it.startsWith("$path/") }

  override fun resolve(name: String): SourceFile = MemorySource(files, if (path.isEmpty()) name else "$path/$name")

  override fun listFiles(): List<SourceFile>? {
    if (!isDirectory) return null
    val prefix = if (path.isEmpty()) "" else "$path/"
    return files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }.distinct().map(::resolve)
  }

  override fun readBytes(): ByteArray = files[path] ?: throw NoSuchElementException("$path: no such file")

  override fun writeBytes(bytes: ByteArray) { files[path] = bytes }

  override fun delete() { files.remove(path) }

  override fun toString(): String = path

  companion object {
    /** A tree of text files. */
    fun of(vararg files: Pair<String, String>) = MemorySource(files.associate { it.first to it.second.encodeToByteArray() }.toMutableMap())
  }
}
