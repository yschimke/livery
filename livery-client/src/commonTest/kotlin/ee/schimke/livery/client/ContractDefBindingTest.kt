package ee.schimke.livery.client

import ee.schimke.livery.protocol.ActionKinds
import ee.schimke.livery.protocol.ContractDef
import ee.schimke.livery.protocol.DataSource
import ee.schimke.livery.protocol.NamedValue
import ee.schimke.livery.protocol.ThemeRoles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** A [ContractDef]'s examples and datasets pass the one gate, `ContractBinder`, as declared. */
class ContractDefBindingTest {
  private object Session : ContractDef("demo/session", major = 1, title = "Session card", dataSource = DataSource.App) {
    val title = string("title", required = true, maxLength = 40)
    val room = string("room", maxLength = 30)
    val seats = int("seats", min = 0, max = 500)
    val live = bool("live", default = false)
    val accent = color("accent")
    val photo = image("photo")
    val theme = themeRoles(ThemeRoles.MATERIAL3)
    val open = action("open", "app:open-session")
    val tickets = action("tickets", ActionKinds.OPEN_URL, arg = "url")

    override val examples
      get() =
        mapOf(
          "default" to mapOf(title to "Keynote", room to "Hall 1", seats to 120, live to true, accent to 0xFF6750A4, photo to "https://example.com/p.png"),
          "minimal" to mapOf(title to "Keynote"),
        )
  }

  @Test fun everyExampleBinds() {
    val contract = Session.toContract()
    contract.examples.forEach { (name, data) -> assertIs<BindResult.Ok>(ContractBinder.bind(contract, data), name) }
    val ok = assertIs<BindResult.Ok>(ContractBinder.bind(contract, contract.examples.getValue("default")))
    assertEquals(NamedValue.Color(0xFF6750A4), ok.bindings.values["accent"])
    assertEquals(NamedValue.I(120), ok.bindings.values["seats"])
    assertEquals(NamedValue.B(false), assertIs<BindResult.Ok>(ContractBinder.bind(contract, contract.examples.getValue("minimal"))).bindings.values["live"])
  }

  @Test fun theGateStillRefusesBadData() {
    val contract = Session.toContract()
    val r = assertIs<BindResult.Invalid>(ContractBinder.bind(contract, Session.data(mapOf(Session.title to "x".repeat(41), Session.seats to 501))))
    assertEquals(setOf("title", "seats"), r.problems.map { it.name }.toSet())
  }

  @Test fun aSingleStringArgumentPassesTheActionGate() {
    val contract = Session.toContract()
    val sent = ActionArguments.parse("""{"url":"https://example.com"}""")!!
    assertEquals(null, ActionGate.check(contract, Session.tickets.name, sent).problem)
    assertEquals("missing argument 'url'", ActionGate.check(contract, Session.tickets.name, emptyMap()).problem)
    assertEquals(emptyList(), ActionGate.declarationProblems(Session.tickets.name, Session.tickets.spec))
  }
}
