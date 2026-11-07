package ee.schimke.livery.a2ui.compose

import androidx.a2ui.compose.ui.toJsonSchemaString
import androidx.a2ui.model.protocol.A2uiClientErrorMessage
import androidx.a2ui.model.protocol.A2uiClientEventMessage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import ee.schimke.livery.protocol.TemplateEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two-way binding: the two-way sample catalog's `shop/quantity@1`, whose `setQuantity` is a `set` of its
 * `quantity` input (PROTOCOL.md §7.1). The agent binds `quantity` to `/cart/qty`; the template's
 * `+` and `−` write the new quantity there, through A2UI's own data-model updater, and the value
 * comes back into the document through the same binding. An A2UI `Button` beside it reads
 * `/cart/qty` into its event, so the test sees the data model exactly as the agent would.
 */
@OptIn(ExperimentalTestApi::class)
class QuantityA2uiTest : LiveryA2uiSurfaceTest() {
  private fun data(qty: Int) = """{"version":"v0.9","updateDataModel":{"surfaceId":"$SURFACE","path":"/cart","value":{"qty":$qty}}}"""

  private fun components(quantity: String = """{"path":"/cart/qty"}""") =
    """
    {"version":"v0.9","updateComponents":{"surfaceId":"$SURFACE","components":[
      {"id":"root","component":"Column","children":["item","checkout"]},
      {"id":"item","component":"ShopQuantity","name":"Oat milk, 1 l","price":"€2.49","quantity":$quantity},
      {"id":"checkout","component":"Button","child":"checkout_text","variant":"primary",
        "action":{"event":{"name":"checkout","context":{"qty":{"path":"/cart/qty"}}}}},
      {"id":"checkout_text","component":"Text","text":"Checkout"}
    ]}}
    """.trimIndent()

  /** The quantity in the data model, as the agent's `checkout` event carries it. */
  private fun ComposeUiTest.checkoutQuantity(): Int {
    val before = outbound.count { it is A2uiClientEventMessage && it.type == "checkout" }
    onNodeWithText("Checkout").performClick()
    waitUntil(timeoutMillis = 5_000) { outbound.count { it is A2uiClientEventMessage && it.type == "checkout" } > before }
    return (outbound.filterIsInstance<A2uiClientEventMessage>().last { it.type == "checkout" }.context["qty"] as Number).toInt()
  }

  private fun ComposeUiTest.tap(label: String) {
    onNodeWithText(label, useUnmergedTree = true).performClick()
    waitForIdle()
  }

  @Test fun theAgentIsToldToBindTheInputASetWrites() {
    val schema = catalog.toJsonSchemaString()
    assertTrue("Writes quantity back through its data-model path" in schema, "the schema says how to bind it")
  }

  @Test fun theTemplatesControlsWriteTheDataModelAndTheDocumentFollows() = runComposeUiTest {
    send(create, data(1), components())
    show()
    val one = capture("quantity-1")
    tap("+")
    tap("+")
    val three = capture("quantity-3")
    assertFalse(sameImage(one, three), "the document shows the new quantity")
    assertEquals(3, checkoutQuantity(), "written to /cart/qty, where the rest of the surface reads it")
    tap("−")
    assertEquals(2, checkoutQuantity())
    assertEquals(3, events.count { it.type == TemplateEvent.Type.Action && it.action == "setQuantity" }, "each step is an Action event with its value")
    assertEquals(1, events.count { it.type == TemplateEvent.Type.Impression }, "the same document throughout")
  }

  @Test fun theAgentsOwnUpdateIsWhatTheNextStepStartsFrom() = runComposeUiTest {
    send(create, data(1), components())
    show()
    send(data(5))
    waitForIdle()
    tap("−")
    assertEquals(4, checkoutQuantity(), "the document computed 5 − 1 from the value the agent sent")
  }

  @Test fun aStepPastTheContractsLimitIsRefused() = runComposeUiTest {
    send(create, data(9), components())
    show()
    tap("+")
    assertEquals(9, checkoutQuantity(), "10 is above quantity's max of 9: nothing is written")
    assertTrue(events.any { it.type == TemplateEvent.Type.Error && it.action == "setQuantity" && "above" in (it.detail ?: "") }, events.toString())
  }

  @Test fun aLiteralHasNowhereToWriteAndTheAgentIsTold() = runComposeUiTest {
    send(create, data(1), components(quantity = "1"))
    show()
    tap("+")
    waitUntil(timeoutMillis = 5_000) { outbound.any { it is A2uiClientErrorMessage } }
    val error = outbound.filterIsInstance<A2uiClientErrorMessage>().first()
    assertTrue("not bound to a data-model path" in error.message, error.message)
    assertTrue(events.any { it.type == TemplateEvent.Type.Error && it.action == "setQuantity" })
  }
}
