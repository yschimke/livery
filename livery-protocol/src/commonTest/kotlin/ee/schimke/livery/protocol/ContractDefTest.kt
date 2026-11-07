package ee.schimke.livery.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A card contract written the way an app would: handles as properties, examples by handle. */
private object CardContract : ContractDef("demo/card", major = 2, minor = 1, title = "Card", dataSource = DataSource.Feed) {
  val title = string("title", required = true, maxLength = 40, description = "the headline")
  val subtitle = string("subtitle", maxLength = 60, default = "", since = 1)
  val count = int("count", min = 0, max = 99, default = 0)
  val size = long("size", min = 0)
  val progress = float("progress", min = 0.0, max = 1.0)
  val live = bool("live", default = false)
  val accent = color("accent", default = 0xFF6750A4)
  val hero = image("hero")
  val theme = themeRoles()
  val open = action("open", "app:open-card")
  val link = action("link", ActionKinds.OPEN_URL, arg = "url", maxLength = 200)
  val share = action("share", "app:share", arg = "value", since = 1)
  val toggle = setAction("toggle", live)

  override val examples
    get() =
      mapOf(
        "default" to mapOf(title to "Hello", count to 3, live to true, accent to 0xFF112233, progress to 0.5, subtitle to null),
        "minimal" to mapOf(title to "Hi"),
      )
}

private object OtherContract : ContractDef("demo/other", major = 1) {
  val name = string("name")
}

class ContractDefTest {
  private val json = Json { encodeDefaults = false }

  @Test fun toContractIsTheProtocolModelInDeclarationOrder() {
    val c = CardContract.toContract()
    assertEquals("demo/card@2", CardContract.ref)
    assertEquals(c.ref, CardContract.ref)
    assertEquals(1, c.minor)
    assertEquals("Card", c.title)
    assertEquals(DataSource.Feed, c.dataSource)
    assertEquals(
      listOf("title", "subtitle", "count", "size", "progress", "live", "accent", "hero") + ThemeRoles.CORE,
      c.inputs.keys.toList(),
    )
    assertEquals(listOf("open", "link", "share", "toggle"), c.actions.keys.toList())
    assertEquals(InputSpec(ValueType.String, required = true, maxLength = 40, description = "the headline"), c.inputs["title"])
    assertEquals(InputSpec(ValueType.Int, default = JsonPrimitive(0), min = 0.0, max = 99.0), c.inputs["count"])
    assertEquals(InputSpec(ValueType.Color, default = JsonPrimitive("#FF6750A4")), c.inputs["accent"])
    assertEquals(InputSpec(ValueType.Color, role = "primary"), c.inputs[ThemeRoles.PRIMARY])
    assertSame(CardContract.title.spec, c.inputs["title"])
  }

  @Test fun actionsWithOneStringArgument() {
    val link = CardContract.link.spec
    assertEquals(ActionKinds.OPEN_URL, link.kind)
    assertEquals(mapOf("url" to InputSpec(ValueType.String, required = true, maxLength = 200)), link.args)
    assertEquals(setOf("value"), CardContract.share.spec.args.keys)
    assertEquals(1, CardContract.share.spec.since)
    assertEquals(ActionSpec(ActionKinds.SET, input = "live"), CardContract.toggle.spec)
    assertEquals(emptyMap(), CardContract.open.spec.args)
  }

  @Test fun roundTripsThroughTheContractSerializer() {
    val c = CardContract.toContract()
    val text = json.encodeToString(Contract.serializer(), c)
    val back = json.decodeFromString(Contract.serializer(), text)
    assertEquals(c, back)
    assertEquals(c.inputs.keys.toList(), back.inputs.keys.toList())
    assertEquals(c.examples.keys.toList(), back.examples.keys.toList())
  }

  @Test fun examplesAreKeyedByHandle() {
    val examples = CardContract.toContract().examples
    assertEquals(listOf("default", "minimal"), examples.keys.toList())
    assertEquals(
      buildJsonObject {
        put("title", "Hello")
        put("count", 3)
        put("live", true)
        put("accent", "#FF112233")
        put("progress", 0.5)
      },
      examples["default"],
    )
    assertNull(examples.getValue("default")["subtitle"], "a null value is left out")
  }

  @Test fun aForeignHandleFails() {
    assertFailsWith<IllegalArgumentException> { CardContract.data(mapOf(OtherContract.name to "x")) }
    val bad =
      object : ContractDef("demo/bad", 1) {
        val own = string("own")
        override val examples get() = mapOf("default" to mapOf(own to "a", OtherContract.name to "b"))
      }
    assertFailsWith<IllegalArgumentException> { bad.toContract() }
  }

  @Test fun duplicateNamesFailFast() {
    assertFailsWith<IllegalArgumentException> {
      object : ContractDef("demo/dup", 1) {
        val a = string("a")
        val b = bool("a")
      }
    }
    assertFailsWith<IllegalArgumentException> {
      object : ContractDef("demo/dup", 1) {
        val a = action("a", ActionKinds.DISMISS)
        val b = action("a", ActionKinds.TRACK)
      }
    }
    assertFailsWith<IllegalArgumentException> {
      object : ContractDef("demo/dup", 1) {
        val primary = color(ThemeRoles.PRIMARY)
        val theme = themeRoles()
      }
    }
    // Inputs and actions are separate namespaces, as in the protocol.
    object : ContractDef("demo/ok", 1) {
      val cta = string("cta")
      val ctaAction = action("cta", ActionKinds.OPEN_URL, arg = "url")
    }
  }

  @Test fun sinceFollowsTheEvolutionRules() {
    assertFailsWith<IllegalArgumentException> { object : ContractDef("demo/since", 1, minor = 1) { val x = string("x", since = 2) } }
    assertFailsWith<IllegalArgumentException> { object : ContractDef("demo/since", 1, minor = 1) { val x = string("x", required = true, since = 1) } }
    assertFailsWith<IllegalArgumentException> { object : ContractDef("demo/since", 1) { val x = action("x", ActionKinds.TRACK, since = 1) } }
  }

  @Test fun setActionNeedsItsOwnInput() {
    assertFailsWith<IllegalArgumentException> { object : ContractDef("demo/set", 1) { val x = setAction("x", OtherContract.name) } }
  }

  @Test fun themeRoles() {
    assertEquals(36, ThemeRoles.MATERIAL3.size)
    assertEquals(ThemeRoles.MATERIAL3.size, ThemeRoles.MATERIAL3.toSet().size)
    assertTrue(ThemeRoles.MATERIAL3.containsAll(ThemeRoles.CORE))
    assertEquals("onSurfaceVariant", ThemeRoles.roleOf(ThemeRoles.ON_SURFACE_VARIANT))
    assertEquals(ThemeRoles.SURFACE, ThemeRoles.nameOf("surface"))

    val all = object : ContractDef("demo/themed", 1) { val theme = themeRoles(ThemeRoles.MATERIAL3) }
    assertEquals(ThemeRoles.MATERIAL3, all.theme.keys.toList())
    all.theme.forEach { (name, input) ->
      assertEquals(name, input.name)
      assertEquals(InputSpec(ValueType.Color, role = ThemeRoles.roleOf(name)), input.spec)
    }
    assertSame(CardContract.theme.getValue(ThemeRoles.SURFACE), CardContract.inputs.single { it.name == ThemeRoles.SURFACE })
    assertFailsWith<IllegalArgumentException> { object : ContractDef("demo/themed", 1) { val t = themeRoles(listOf("primary")) } }
  }
}
