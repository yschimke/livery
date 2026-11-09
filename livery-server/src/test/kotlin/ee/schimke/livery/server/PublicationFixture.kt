package ee.schimke.livery.server

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Opaque RC bytes exercise publication transport, not contract binding or rendering. */
internal class PublicationFixture {
  val directory: Path = Files.createTempDirectory("livery-publication-test-")
  val root: Path = directory.resolve("publications")
  val viewer: Path = directory.resolve("viewer")
  val template = "opaque artifact for publication transport tests".toByteArray()
  val templateHash = sha(template)

  init {
    Files.createDirectories(viewer)
    Files.writeString(viewer.resolve("viewer.js"), "// operator viewer")
    Files.writeString(viewer.resolve("viewer.css"), "body { color: black; }")
    Files.writeString(viewer.resolve("credentials.json"), "must not be served")
  }

  fun bundle(
    publicationId: String = "quantity",
    revision: String = "one",
    title: String = "Quantity & preview",
  ): Path {
    val path = root.resolve("$publicationId/r/$revision")
    Files.createDirectories(path.resolve("catalog/templates"))
    Files.writeString(
      path.resolve("preview.json"),
      """{
        "schemaVersion":1,"mode":"preview","publicationId":${JsonPrimitive(publicationId)},"revision":${JsonPrimitive(revision)},
        "canonicalUrl":"./","catalog":{"manifestUrl":"./catalog/manifest.json"},
        "template":{"contract":"shop/quantity@1","designId":"quantity-stepper","sha256":"$templateHash"},
        "data":{"quantity":1},"fallback":{"title":${JsonPrimitive(title)},"description":"Local example only"}
      }""",
    )
    Files.writeString(
      path.resolve("catalog/manifest.json"),
      """{
        "catalog":"transport-test","serial":1,"generatedAtEpochMs":1,"expiresAtEpochMs":2,"blobBase":"templates/",
        "contracts":[{"id":"shop/quantity","major":1}],"designs":[{"id":"quantity-stepper","contract":"shop/quantity@1"}],
        "templates":[{"sha256":"$templateHash","bytes":${template.size},"contract":"shop/quantity@1","designId":"quantity-stepper"}]
      }""",
    )
    Files.write(path.resolve("catalog/templates/$templateHash.rc"), template)
    Files.write(
      path.resolve("thumbnail.png"),
      Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a9n8AAAAASUVORK5CYII="),
    )
    seal(path)
    return path
  }

  fun descriptor(path: Path): JsonObject = Json.parseToJsonElement(Files.readString(path.resolve("preview.json"))).jsonObject

  fun writeDescriptor(path: Path, descriptor: JsonObject) {
    Files.writeString(path.resolve("preview.json"), descriptor.toString())
    seal(path)
  }

  fun seal(path: Path) {
    val inventory = Files.walk(path).use { files ->
      files.filter { Files.isRegularFile(it) && it.fileName.toString() !in setOf("publication-files.json", "withdrawn") }
        .toList().associate { path.relativize(it).toString().replace('\\', '/') to JsonPrimitive(sha(Files.readAllBytes(it))) }
    }
    Files.writeString(path.resolve("publication-files.json"), JsonObject(inventory).toString())
  }

  fun close() {
    Files.walk(directory).use { files -> files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
  }

  companion object {
    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  }
}
