package ee.schimke.livery.publisher

import ee.schimke.livery.a2ui.LiveryA2ui
import ee.schimke.livery.protocol.Manifest
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * `a2ui-catalog.json` beside a built catalog's manifest (docs/A2UI.md): its contracts as A2UI
 * components, one per contract major at its newest minor, the same schema a client's
 * livery-a2ui-compose advertises. An agent in any language is prompted with it; a client still
 * advertises only the components it can draw.
 */
object A2uiCatalogFile {
  const val NAME: String = "a2ui-catalog.json"

  fun schema(catalogId: String, manifest: Manifest): JsonObject = LiveryA2ui.catalogSchema(catalogId, manifest.contracts)

  fun write(out: File, catalogId: String, manifest: Manifest): File =
    File(out, NAME).apply { writeText(JSON.encodeToString(JsonObject.serializer(), schema(catalogId, manifest)) + "\n") }

  private val JSON = Json { prettyPrint = true }
}
