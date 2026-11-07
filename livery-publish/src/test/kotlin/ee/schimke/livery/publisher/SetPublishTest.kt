package ee.schimke.livery.publisher

import ee.schimke.livery.protocol.ActionKinds
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** PROTOCOL.md §7.1 in the publisher: `set` actions name a scalar input of their own contract. */
class SetPublishTest {
  private val sample = File(System.getProperty("user.dir")).resolve("../samples/two-way").canonicalFile

  /** The two-way sample, with the quantity contract's `setQuantity` replaced by [action]. */
  private fun tree(action: String): File = Files.createTempDirectory("set").toFile().also { src ->
    sample.copyRecursively(src)
    File(src, "contracts/quantity.json").let { f ->
      f.writeText(f.readText().replace("""{ "kind": "set", "input": "quantity", "description": "the person stepped the quantity; the host writes it back" }""", action))
    }
  }

  private fun problems(action: String) = assertFailsWith<CatalogProblems> { CatalogBuilder().build(tree(action), Layout.Static) }.problems

  @Test fun aCatalogWithASetNeedsLevelSeven() {
    val m = CatalogBuilder(now = { 0 }).build(sample, Layout.Static, serial = 1).manifest
    assertEquals(SET_LEVEL, m.minClient)
    assertEquals("quantity", m.contracts.single { it.id == "shop/quantity" }.actions.getValue("setQuantity").input)
    assertTrue(m.contracts.single { it.id == "shop/quantity" }.actions.values.all { it.kind == ActionKinds.SET })
  }

  @Test fun aSetNamesAnInputOfItsContract() {
    assertTrue(problems("""{ "kind": "set" }""").any { "action setQuantity is `set` but names no input" in it })
    assertTrue(problems("""{ "kind": "set", "input": "qty" }""").any { "sets qty, which isn't one of the contract's inputs" in it })
    assertTrue(problems("""{ "kind": "set", "input": "theme.primary" }""").any { "a theme role, which the host fills" in it })
  }

  @Test fun aSetCarriesOnlyItsInputsValue() {
    val p = problems("""{ "kind": "set", "input": "quantity", "args": { "step": { "type": "int" } } }""")
    assertTrue(p.any { "its one argument is value, checked as quantity is, so it declares none" in it }, p.toString())
  }

  @Test fun onlyASetNamesAnInput() {
    assertTrue(problems("""{ "kind": "app:step", "input": "quantity" }""").any { "action setQuantity names an input but isn't `set`" in it })
  }
}
