package ee.schimke.livery.client

import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.InputSpec
import ee.schimke.livery.protocol.ListSpec
import ee.schimke.livery.protocol.NamedValue
import ee.schimke.livery.protocol.ValueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class BindingTest {
  private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

  @Test fun flattensInputsAndLists() {
    val c = Contract("feed/article", 1,
      inputs = mapOf("title" to InputSpec(ValueType.String, required = true), "accent" to InputSpec(ValueType.Color, default = Json.parseToJsonElement("\"#112233\""))),
      lists = mapOf("tags" to ListSpec(mapOf("label" to InputSpec(ValueType.String)), max = 2)))
    val r = assertIs<BindResult.Ok>(ContractBinder.bind(c, obj("""{"title":"Hi","tags":[{"label":"a"},{"label":"b"}]}""")))
    assertEquals(NamedValue.Str("Hi"), r.bindings.values["title"])
    assertEquals(NamedValue.Color(0xFF112233), r.bindings.values["accent"])
    assertEquals(NamedValue.I(2), r.bindings.values["tags.count"])
    assertEquals(NamedValue.Str("b"), r.bindings.values["tags.1.label"])
  }

  @Test fun refusesWhatTheContractDoesNotAllow() {
    val r = assertIs<BindResult.Invalid>(ContractBinder.bind(Fx.tile, obj("""{"name":"Far too long a name","level":2,"typo":1,"is_on":"yes"}""")))
    assertEquals(setOf("typo", "name", "level", "is_on"), r.problems.map { it.name }.toSet())
  }

  @Test fun requiredMissing() {
    val r = assertIs<BindResult.Invalid>(ContractBinder.bind(Fx.tile, obj("{}")))
    assertEquals(listOf("name"), r.problems.map { it.name })
  }
}
