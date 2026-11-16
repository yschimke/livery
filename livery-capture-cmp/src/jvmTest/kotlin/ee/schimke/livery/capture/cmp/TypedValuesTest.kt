package ee.schimke.livery.capture.cmp

import ee.schimke.composeai.rccreation.compose.capture.RemoteDensityBehavior
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteImage
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.clickable
import ee.schimke.composeai.rccreation.compose.modifier.visibility
import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.livery.instantiate.TemplateInspector
import ee.schimke.livery.protocol.ActionKinds
import ee.schimke.livery.protocol.ContractDef
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.Target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private object TalkContract : ContractDef("demo/talk", major = 1) {
  val title = string("title", required = true, maxLength = 40)
  val photo = image("photo")
  val tickets = string("tickets")
  val speakers = list("speakers", max = 2) { string("name", maxLength = 30) }
  val speakerName = speakers.field("name")
  val open = action("open", "app:open")
  val buy = action("buy", ActionKinds.OPEN_URL, arg = "url")
}

private object OtherContract : ContractDef("demo/other", major = 1) {
  val name = string("name")
}

/** A template written against a [ContractDef]'s handles reads and emits exactly what it names. */
class TypedValuesTest {
  private val inApp = Target(Surfaces.IN_APP, 7, listOf("androidx"))

  @Test fun handlesNameTheValuesListsImagesAndActions() = runBlocking {
    val t = LiveryCapture.capture(inApp, 300, 120, densityBehavior = RemoteDensityBehavior.Dp, fonts = listOf("Space Mono")) {
      val v = LiveryValues(TalkContract)
      RemoteColumn(RemoteModifier.clickable(v.action(TalkContract.open))) {
        RemoteText(v.string(TalkContract.title))
        RemoteImage(v.image(TalkContract.photo), contentDescription = null)
        for (i in 0 until 2) {
          RemoteText(
            v.string(TalkContract.speakers, i, TalkContract.speakerName),
            RemoteModifier.visibility(v.count(TalkContract.speakers)),
          )
        }
        RemoteText("Tickets".rs, RemoteModifier.clickable(v.action(TalkContract.buy, v.string(TalkContract.tickets))))
      }
    }
    assertEquals(
      listOf("photo", "speakers.0.name", "speakers.1.name", "speakers.count", "tickets", "title"),
      t.reads,
    )
    assertEquals(listOf("buy", "open"), t.emits)
    assertEquals(listOf("Space Mono"), t.fonts)
    assertTrue("\"fonts\"" in t.sidecar())
    assertEquals(emptyList(), TemplateInspector.typeProblems(t.bytes, TalkContract.toContract()), "the publisher's type check passes")
  }

  @Test fun aHandleOfAnotherContractFailsTheCapture() {
    assertFailsWith<IllegalArgumentException> {
      runBlocking { LiveryCapture.capture(inApp, 300, 120) { RemoteText(LiveryValues(TalkContract).string(OtherContract.name)) } }
    }
  }
}
