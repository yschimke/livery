package ee.schimke.livery.a2ui

import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.ValueType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `conformance/a2ui.json`: Livery in A2UI's terms, so every renderer (this one, and the web and Swift
 * ones to come) gives an agent the same components and turns the same A2UI values into the same
 * datasets and events. Regenerate with `-Plivery.conformance.update=true`.
 */
class A2uiConformanceTest {
  private val root = File(System.getProperty("user.dir")).resolve("..").canonicalFile
  private val dir = File(root, "conformance")
  private val update = System.getProperty("livery.conformance.update") == "true"
  private val json = Json { prettyPrint = true; encodeDefaults = false; prettyPrintIndent = "  " }
  private val read = Json { ignoreUnknownKeys = true }

  private fun contract(path: String) = read.decodeFromString(Contract.serializer(), File(root, path).readText())

  private val banner = contract("samples/catalog/contracts/promo-banner.json")
  private val poll = contract("samples/catalog/contracts/poll.json")
  private val tile = contract("samples/catalog/contracts/status-tile.json")
  private val article = contract("samples/catalog/contracts/feed-article.json")
  private val quantity = contract("samples/two-way/contracts/quantity.json")

  @Test fun a2ui() {
    val contracts = listOf(banner, poll, tile, article, quantity)
    val fixture = buildJsonObject {
      put("note", "Livery in A2UI's terms (docs/A2UI.md): the component each contract becomes, and the data and events that cross between A2UI and Livery.")
      put("components", JsonArray(contracts.map { c ->
        val spec = LiveryA2ui.component(c)
        buildJsonObject {
          put("contract", json.encodeToJsonElement(Contract.serializer(), c))
          put("name", spec.name)
          put("schema", spec.schema())
        }
      }))
      put("names", JsonArray(listOf(Contract("promo/banner", 1), Contract("promo/banner", 2), Contract("ha-card/tile_v2.x", 1)).map { c ->
        buildJsonObject { put("contract", c.ref); put("name", LiveryA2ui.componentName(c)) }
      }))
      put("datasets", buildJsonArray {
        fun case(name: String, c: Contract, resolved: Map<String, Any?>) = add(buildJsonObject {
          put("name", name); put("contract", c.ref)
          put("resolved", LiveryA2ui.toJson(resolved))
          put("expect", LiveryA2ui.dataset(c, resolved))
        })
        case("inputs as resolved; unbound and pending ones left out", banner, mapOf("headline" to "Try Premium free", "cta" to "Start trial", "body" to null))
        case("component properties and theme roles are not data", banner, mapOf("headline" to "Hi", "design" to "banner-classic", "actions" to mapOf<String, Any?>(), "theme.primary" to "#FF000000"))
        case("a list as an array of objects", poll, mapOf("question" to "Which?", "options" to listOf(mapOf("label" to "Kotlin", "share" to 0.52), mapOf("label" to "Swift", "share" to 0.48))))
        case("a number is passed as given, for ContractBinder to judge", article, mapOf("title" to "Remote Compose", "published" to 1e20))
        case("a whole number, as A2UI resolves it (a double)", quantity, mapOf("name" to "Oat milk", "quantity" to 3.0))
      })
      put("themes", buildJsonArray {
        val roles = mapOf("primary" to 0xFF6750A4L, "surface" to 0xFFFEF7FFL, "onSurface" to 0xFF1D1B20L, "error" to 0xFFB3261EL)
        for (c in listOf(banner, poll)) add(buildJsonObject {
          put("contract", c.ref)
          put("argbByRole", buildJsonObject { roles.forEach { (k, v) -> put(k, v) } })
          put("expect", LiveryA2ui.themeValues(c, roles))
        })
      })
      put("writable", buildJsonArray {
        for ((type, value) in listOf(ValueType.Int to "3", ValueType.Long to "1793000000000", ValueType.Float to "0.5", ValueType.Bool to "true", ValueType.Bool to "false", ValueType.String to "", ValueType.String to "{\"a\":1}", ValueType.Int to "x")) add(buildJsonObject {
          put("type", type.name.lowercase()); put("value", value)
          put("expect", LiveryA2ui.toJson(LiveryA2ui.writable(value, type)))
        })
      })
      put("events", buildJsonArray {
        fun case(name: String, action: Map<String, Any?>, args: Map<String, String>) = add(buildJsonObject {
          val p = LiveryA2ui.eventPayload(action, args)
          put("name", name)
          put("action", LiveryA2ui.toJson(action)); put("args", LiveryA2ui.toJson(args))
          put("expect", buildJsonObject { put("action", LiveryA2ui.toJson(p.action)); put("shadowed", JsonArray(p.shadowed.map(::JsonPrimitive))) })
        })
        case("the template's arguments join the agent's context", mapOf("event" to mapOf("name" to "vote", "context" to mapOf("poll" to "ui-language"))), mapOf("option" to "1"))
        case("no context of the agent's own", mapOf("event" to mapOf("name" to "startTrial")), mapOf("route" to "/premium"))
        case("an argument wins over a context key it shadows, which is reported", mapOf("event" to mapOf("name" to "vote", "context" to mapOf("option" to "9", "poll" to "p"))), mapOf("option" to "1"))
        case("a function call passes as it is", mapOf("functionCall" to mapOf("call" to "openUrl", "args" to mapOf("url" to "https://example.com"))), mapOf("route" to "/x"))
      })
    }
    check("a2ui.json", fixture)
  }

  @Test fun theCatalogSchemaIsTheComponentsAtTheirNewestMinor() {
    val newer = banner.copy(minor = 1, inputs = banner.inputs + ("badge" to banner.inputs.getValue("cta").copy(required = false, since = 1)))
    val schema = LiveryA2ui.catalogSchema("https://example.com/a2ui/v1", listOf(banner, newer, poll))
    assertEquals(JsonPrimitive("https://example.com/a2ui/v1"), schema["catalogId"])
    val components = schema.getValue("components") as kotlinx.serialization.json.JsonObject
    assertEquals(setOf("PromoBanner", "SocialPoll"), components.keys)
    assertEquals(LiveryA2ui.component(newer).schema(), components["PromoBanner"])
  }

  private fun check(name: String, actual: JsonElement) {
    val file = File(dir, name)
    val text = json.encodeToString(JsonElement.serializer(), actual) + "\n"
    if (update || !file.isFile) { dir.mkdirs(); file.writeText(text); return }
    assertEquals(file.readText(), text, "conformance/$name drifted: rerun with -Plivery.conformance.update=true if intended")
  }

}
