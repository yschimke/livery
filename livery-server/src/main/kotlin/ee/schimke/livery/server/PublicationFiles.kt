package ee.schimke.livery.server

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

/** Shared filesystem boundaries for installed publications and operator-supplied viewer assets. */
internal object PublicationFiles {
  val name = Regex("[a-z0-9][a-z0-9-]{0,63}")
  val sha256 = Regex("[a-f0-9]{64}")
  const val maxDescriptorBytes = 64 * 1024
  const val maxBundleBytes = 32 * 1024 * 1024
  private val segment = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}")

  fun validPath(relative: String): Boolean =
    relative.length <= 512 && relative.split('/').all {
      segment.matches(it) && it != "." && it != ".."
    }

  /** Check every component without following links, including the configured root's parents. */
  fun safeFile(root: Path, relative: String): Path? {
    if (!validPath(relative)) return null
    val base = root.toAbsolutePath().normalize()
    var checked = base.root
    for (part in base) {
      checked = checked.resolve(part)
      if (Files.isSymbolicLink(checked)) return null
    }
    var file = base
    for (part in relative.split('/')) {
      file = file.resolve(part)
      if (Files.isSymbolicLink(file)) return null
    }
    return file.takeIf { it.startsWith(base) && Files.isRegularFile(it, NOFOLLOW_LINKS) }
  }

  fun boundedRead(path: Path, limit: Int): ByteArray {
    require(limit >= 0 && Files.size(path) <= limit)
    return Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
      input.readNBytes(limit + 1).also { require(it.size <= limit) }
    }
  }

  fun digest(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  fun publicationType(path: String): String? {
    if (!validPath(path)) return null
    return when {
      path == "preview.json" -> "application/vnd.livery.preview+json"
      path == "catalog/manifest.json" -> "application/json"
      path == "thumbnail.png" -> "image/png"
      !path.startsWith("catalog/") -> null
      else -> when (path.substringAfterLast('.')) {
        "json" -> "application/json"
        "rc", "bin", "ttf", "otf", "woff2" -> "application/octet-stream"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> null
      }
    }
  }

  fun viewerType(path: String): String? {
    if (!validPath(path)) return null
    return when (path.substringAfterLast('.')) {
      "js", "mjs" -> "application/javascript"
      "css" -> "text/css"
      "wasm" -> "application/wasm"
      else -> null
    }
  }
}
