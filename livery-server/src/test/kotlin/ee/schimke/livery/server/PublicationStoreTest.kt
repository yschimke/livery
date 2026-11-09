package ee.schimke.livery.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PublicationStoreTest {
  private val fixture = PublicationFixture()
  private val store = PublicationStore(fixture.root)

  @AfterTest fun cleanup() = fixture.close()

  @Test fun `installer and store share publication identity metadata and UTF-8 boundaries`() {
    val file = Path.of(checkNotNull(System.getProperty("livery.publication.cases")))
    val cases = Json.parseToJsonElement(Files.readString(file)).jsonObject
    assertEquals(1, cases.getValue("formatVersion").jsonPrimitive.int)
    for (element in cases.getValue("cases").jsonArray) {
      val row = element.jsonObject
      val name = row.getValue("name").jsonPrimitive.content
      val field = row.getValue("field").jsonPrimitive.content
      val value = row.getValue("text").jsonPrimitive.content.repeat(row["repeat"]?.jsonPrimitive?.int ?: 1)
      val id = if (field == "publicationId") value else "quantity"
      val revision = if (field == "revision") value else "one"
      val safeNames = listOf(id, revision).all { Regex("[a-z0-9][a-z0-9-]{0,63}").matches(it) }
      val path = fixture.bundle(if (safeNames) id else "quantity", if (safeNames) revision else "one")
      val original = fixture.descriptor(path)
      val descriptor = when (field) {
        "publicationId", "revision" -> JsonObject(original + (field to JsonPrimitive(value)))
        "title", "description" -> JsonObject(original + ("fallback" to JsonObject(original.getValue("fallback").jsonObject + (field to JsonPrimitive(value)))))
        "descriptorBytes" -> JsonObject(original + ("data" to JsonObject(original.getValue("data").jsonObject + ("notes" to JsonPrimitive(value)))))
        else -> error("Unknown shared fixture field: $field")
      }
      var bytes = descriptor.toString()
      if (field == "descriptorBytes") {
        val size = row.getValue("bytes").jsonPrimitive.int
        bytes += " ".repeat(size - bytes.toByteArray(Charsets.UTF_8).size)
        assertEquals(size, bytes.toByteArray(Charsets.UTF_8).size, name)
        assertTrue(bytes.length < size, "fixture must exercise multibyte UTF-8: $name")
      }
      Files.writeString(path.resolve("preview.json"), bytes)
      fixture.seal(path)
      assertEquals(row.getValue("accepted").jsonPrimitive.boolean, store.read(id, revision) is PublicationResult.Ready, name)
    }
  }

  @Test fun `ready snapshots contain only verified allowlisted public bytes and remain pinned`() {
    val path = fixture.bundle()
    Files.writeString(path.resolve("private-editor-history.json"), "must not leak")
    Files.writeString(fixture.directory.resolve("private-state.json"), "must not leak")
    val first = assertIs<PublicationResult.Ready>(store.read("quantity", "one")).snapshot
    assertEquals("quantity", first.publicationId)
    assertEquals("one", first.revision)
    assertEquals("Quantity & preview", first.title)
    assertEquals(setOf("preview.json", "thumbnail.png", "catalog/manifest.json", "catalog/templates/${fixture.templateHash}.rc"), first.files.keys)
    assertFalse(first.files.containsKey("publication-files.json"))
    assertContentEquals(fixture.template, first.files.getValue("catalog/templates/${fixture.templateHash}.rc"))
    fixture.bundle(revision = "two", title = "New title")
    val reread = assertIs<PublicationResult.Ready>(store.read("quantity", "one")).snapshot
    assertContentEquals(first.files.getValue("preview.json"), reread.files.getValue("preview.json"))
    // A caller mutating its returned bytes cannot poison a subsequent filesystem snapshot.
    first.files.getValue("thumbnail.png")[0] = 0
    assertEquals(137.toByte(), assertIs<PublicationResult.Ready>(store.read("quantity", "one")).snapshot.files.getValue("thumbnail.png")[0])
  }

  @Test fun `missing malformed and tampered inventory or files make the publication unavailable`() {
    val path = fixture.bundle()
    val inventoryFile = path.resolve("publication-files.json")
    val inventory = Files.readString(inventoryFile)
    Files.delete(inventoryFile)
    assertEquals(PublicationResult.Unavailable, store.read("quantity", "one"))
    Files.writeString(inventoryFile, "not JSON")
    assertEquals(PublicationResult.Unavailable, store.read("quantity", "one"))
    Files.writeString(inventoryFile, inventory)
    Files.writeString(path.resolve("catalog/templates/${fixture.templateHash}.rc"), "modified")
    assertEquals(PublicationResult.Unavailable, store.read("quantity", "one"))
    fixture.bundle()
    Files.delete(path.resolve("thumbnail.png"))
    assertEquals(PublicationResult.Unavailable, store.read("quantity", "one"))
  }

  @Test fun `withdrawal reserves identity even after all artifacts and inventory are removed`() {
    val path = fixture.bundle()
    Files.writeString(path.resolve("withdrawn"), "withdrawn")
    assertEquals(PublicationResult.Withdrawn, store.read("quantity", "one"))
    Files.walk(path).use { files ->
      files.filter { it != path && it.fileName.toString() != "withdrawn" }
        .sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
    assertEquals(PublicationResult.Withdrawn, store.read("quantity", "one"))
  }

  @Test fun `file and ancestor symlinks never expose outside bytes`() {
    val path = fixture.bundle()
    val template = path.resolve("catalog/templates/${fixture.templateHash}.rc")
    val outside = fixture.directory.resolve("external.rc")
    Files.move(template, outside)
    Files.createSymbolicLink(template, outside)
    assertEquals(PublicationResult.Unavailable, store.read("quantity", "one"))
    Files.delete(template)
    Files.move(outside, template)
    val original = path.resolve("catalog/templates")
    val externalDirectory = fixture.directory.resolve("external-directory")
    Files.move(original, externalDirectory)
    Files.createSymbolicLink(original, externalDirectory)
    assertEquals(PublicationResult.Unavailable, store.read("quantity", "one"))
    val alias = fixture.directory.resolve("root-alias")
    Files.createSymbolicLink(alias, fixture.root)
    assertEquals(PublicationResult.Unavailable, PublicationStore(alias).read("quantity", "one"))
  }

  @Test fun `inventory cannot authorize scripts traversal or its own operator marker`() {
    val path = fixture.bundle()
    val inventoryFile = path.resolve("publication-files.json")
    val original = Json.parseToJsonElement(Files.readString(inventoryFile)).jsonObject
    for (name in listOf("../private.json", "catalog/action.js", "publication-files.json", "catalog/%2e%2e/private.json")) {
      Files.writeString(inventoryFile, JsonObject(original + (name to JsonPrimitive("a".repeat(64)))).toString())
      assertEquals(PublicationResult.Unavailable, store.read("quantity", "one"), name)
    }
  }

  @Test fun `descriptor must refer to its own identity relative URLs and inventoried template pin`() {
    val path = fixture.bundle()
    val original = fixture.descriptor(path)
    val rejected = listOf(
      JsonObject(original + ("publicationId" to JsonPrimitive("other"))),
      JsonObject(original + ("revision" to JsonPrimitive("other"))),
      JsonObject(original + ("canonicalUrl" to JsonPrimitive("https://other.example/"))),
      JsonObject(original + ("catalog" to JsonObject(mapOf("manifestUrl" to JsonPrimitive("../catalog/manifest.json"))))),
      JsonObject(original + ("template" to JsonObject(original.getValue("template").jsonObject + ("sha256" to JsonPrimitive("b".repeat(64)))))),
    )
    for (descriptor in rejected) {
      fixture.writeDescriptor(path, descriptor)
      assertEquals(PublicationResult.Unavailable, store.read("quantity", "one"))
    }
  }

  @Test fun `catalog JSON retains its one MiB budget independently of the descriptor limit`() {
    val path = fixture.bundle()
    val manifestFile = path.resolve("catalog/manifest.json")
    val manifest = Files.readString(manifestFile)
    val exact = manifest + " ".repeat(1024 * 1024 - manifest.toByteArray(Charsets.UTF_8).size)
    Files.writeString(manifestFile, exact)
    fixture.seal(path)
    assertIs<PublicationResult.Ready>(store.read("quantity", "one"))
    Files.writeString(manifestFile, exact + " ")
    fixture.seal(path)
    assertEquals(PublicationResult.Unavailable, store.read("quantity", "one"))
  }

  @Test fun `maximum asset path is relative to revision independently of maximum identifier lengths`() {
    val id = "a".repeat(64)
    val revision = "b".repeat(64)
    val path = fixture.bundle(id, revision)
    val relative = "catalog/" + "a".repeat(99) + "/" + "b".repeat(99) + "/" + "c".repeat(28) + ".bin"
    assertEquals(240, relative.length)
    Files.createDirectories(path.resolve(relative).parent)
    Files.writeString(path.resolve(relative), "long path asset")
    fixture.seal(path)
    assertContentEquals("long path asset".toByteArray(), assertIs<PublicationResult.Ready>(store.read(id, revision)).snapshot.files.getValue(relative))
    val oversized = relative.replace("c".repeat(28), "c".repeat(29))
    Files.move(path.resolve(relative), path.resolve(oversized))
    fixture.seal(path)
    assertEquals(PublicationResult.Unavailable, store.read(id, revision))
  }
}
