package ee.schimke.livery.publisher

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** `livery build --a2ui`: the sample's contracts as A2UI components, for an agent to be prompted with. */
class A2uiCatalogFileTest {
  @Test fun theSamplesContractsAreComponents() {
    val m = CatalogBuilder(now = { 0 }).build(File("../samples/catalog"), Layout.Static, serial = 1).manifest
    val file = A2uiCatalogFile.write(Files.createTempDirectory("a2ui").toFile(), "https://example.com/a2ui/v1", m)
    val schema = Json.parseToJsonElement(file.readText()).jsonObject
    assertEquals("https://example.com/a2ui/v1", schema.getValue("catalogId").jsonPrimitive.content)
    assertEquals(setOf("FeedArticle", "LiveStatusTile", "PromoBanner", "SocialPoll"), schema.getValue("components").jsonObject.keys)
    assertTrue("\"theme.primary\"" !in file.readText(), "theme roles are the host's")
  }
}
