package ee.schimke.livery.a2ui.compose

import androidx.a2ui.compose.ui.toJsonSchemaString
import ee.schimke.livery.a2ui.LiveryA2ui
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The schema A2UI itself advertises for each Livery component (`A2uiCatalog.toJsonSchemaString()`,
 * built from the properties livery-a2ui-compose registers) is exactly livery-a2ui's projection, so
 * `livery build --a2ui` and every other renderer offer an agent what this one does.
 */
class ProjectionMatchesA2uiTest : LiveryA2uiSurfaceTest() {
  @Test fun everyLiveryComponentsSchemaIsTheProjection() {
    val advertised = Json.parseToJsonElement(catalog.toJsonSchemaString()).jsonObject.getValue("components").jsonObject
    val livery = catalog.components.filterIsInstance<LiveryA2uiComponent>()
    assertTrue(livery.size >= 4, "the sample's contracts are all components: ${livery.map { it.name }}")
    for (c in livery) {
      assertEquals(normal(LiveryA2ui.component(c.contract).schema()), normal(advertised.getValue(c.name)), c.name)
    }
  }

  /** Key order isn't part of a schema; a `required` list's order isn't either. */
  private fun normal(e: JsonElement): JsonElement = when (e) {
    is JsonObject -> JsonObject(e.entries.sortedBy { it.key }.associate { (k, v) ->
      k to if (k == "required" && v is JsonArray) JsonArray(v.sortedBy { it.toString() }) else normal(v)
    })
    is JsonArray -> JsonArray(e.map(::normal))
    else -> e
  }
}
