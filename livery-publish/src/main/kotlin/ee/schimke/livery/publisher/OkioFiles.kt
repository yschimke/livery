package ee.schimke.livery.publisher

import java.io.File
import okio.ByteString
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/** A file's bytes as one immutable value, read through Okio. */
internal fun File.readByteString(): ByteString = FileSystem.SYSTEM.read(toOkioPath()) { readByteString() }

/** Writes [bytes] to this file, creating its parent directories. */
internal fun File.writeByteString(bytes: ByteString) {
  parentFile?.mkdirs()
  FileSystem.SYSTEM.write(toOkioPath()) { write(bytes) }
}
