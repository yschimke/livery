package ee.schimke.livery.server

import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A fresh read of an installed revision. Invalid, partial and missing installations are unavailable. */
sealed interface PublicationResult {
  data object Unavailable : PublicationResult
  data object Withdrawn : PublicationResult
  data class Ready(val snapshot: PublicationSnapshot) : PublicationResult
}

/**
 * A verified, request-local snapshot. The byte arrays are detached from the filesystem and are
 * never shared by the store across reads. This is artifact integrity, not catalog trust or binding.
 */
data class PublicationSnapshot(
  val publicationId: String,
  val revision: String,
  val title: String,
  val description: String,
  val files: Map<String, ByteArray>,
)

/**
 * Reads complete bundles installed atomically under `root/<id>/r/<revision>/`.
 *
 * This transport-free store performs synchronous filesystem reads. Dispatch them to an IO
 * context when called from an asynchronous host. A `withdrawn` marker takes precedence over
 * inventory validation; every read observes it, including requests with cached validators.
 * The installation inventory is a local marker, not a wire protocol or a source of trust keys.
 * Publisher/client trust, template selection, data binding and actions remain their own gates.
 */
class PublicationStore(private val root: Path) {
  fun read(publicationId: String, revision: String): PublicationResult {
    if (!PublicationFiles.name.matches(publicationId) || !PublicationFiles.name.matches(revision)) {
      return PublicationResult.Unavailable
    }
    val directory = "$publicationId/r/$revision"
    return runCatching {
      if (PublicationFiles.safeFile(root, "$directory/withdrawn") != null) {
        PublicationResult.Withdrawn
      } else {
        readBundle(directory, publicationId, revision)?.let { PublicationResult.Ready(it) }
          ?: PublicationResult.Unavailable
      }
    }.getOrDefault(PublicationResult.Unavailable)
  }

  private fun readBundle(directory: String, id: String, revision: String): PublicationSnapshot? {
    val marker = PublicationFiles.safeFile(root, "$directory/publication-files.json") ?: return null
    val inventory = Json.parseToJsonElement(
      PublicationFiles.boundedRead(marker, 64 * 1024).decodeToString(),
    ) as? JsonObject ?: return null
    require(inventory.size in 3..128)
    require(inventory.keys.containsAll(listOf("preview.json", "catalog/manifest.json", "thumbnail.png")))
    var total = 0
    val files = inventory.mapValues { (name, digest) ->
      require(name.length <= 240 && PublicationFiles.publicationType(name) != null)
      val expected = (digest as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: error("digest missing")
      require(PublicationFiles.sha256.matches(expected))
      val file = PublicationFiles.safeFile(root, "$directory/$name") ?: error("missing artifact")
      val limit = when {
        name == "preview.json" -> PublicationFiles.maxDescriptorBytes
        name.endsWith(".json") -> 1024 * 1024
        else -> 16 * 1024 * 1024
      }
      val bytes = PublicationFiles.boundedRead(file, minOf(limit, PublicationFiles.maxBundleBytes - total))
      total += bytes.size
      require(PublicationFiles.digest(bytes) == expected) { "artifact digest mismatch" }
      bytes
    }
    val descriptor = Json.parseToJsonElement(files.getValue("preview.json").decodeToString()) as? JsonObject
      ?: return null
    require(Json.parseToJsonElement(files.getValue("catalog/manifest.json").decodeToString()) is JsonObject)
    require(descriptor["schemaVersion"] == JsonPrimitive(1) && descriptor.text("mode") == "preview")
    require(descriptor.text("publicationId") == id && descriptor.text("revision") == revision)
    require(descriptor.text("canonicalUrl") == "./")
    require((descriptor["catalog"] as? JsonObject)?.text("manifestUrl") == "./catalog/manifest.json")
    require(descriptor["data"] is JsonObject)
    val template = descriptor["template"] as? JsonObject ?: return null
    require(PublicationFiles.sha256.matches(template.text("sha256")))
    require(template.text("contract").isNotBlank() && template.text("designId").isNotBlank())
    // Readiness only: do not duplicate the publisher/client catalog and contract gates.
    require(inventory.values.any { (it as? JsonPrimitive)?.content == template.text("sha256") })
    val fallback = descriptor["fallback"] as? JsonObject ?: return null
    val title = fallback.text("title")
    val description = fallback.text("description")
    require(title.length in 1..200 && description.length in 1..2000)
    require(files.getValue("thumbnail.png").take(8).toByteArray().contentEquals(PNG_SIGNATURE))
    return PublicationSnapshot(id, revision, title, description, files)
  }

  private fun JsonObject.text(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()

  private companion object {
    val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
  }
}
