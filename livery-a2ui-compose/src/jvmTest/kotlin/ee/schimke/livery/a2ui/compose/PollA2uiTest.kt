package ee.schimke.livery.a2ui.compose

import androidx.a2ui.model.protocol.A2uiClientEventMessage
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
 * The sample catalog's `social/poll@1` in an A2UI surface: data that keeps changing, and an action
 * the agent answers.
 *
 * The agent binds the poll to `/poll` in the surface's data model once. A tap on an option is the
 * template's `vote`, which arrives at the agent as its own `vote` event, with the option and the
 * agent's context. The agent answers as any A2UI agent does, with `updateDataModel`; so do the
 * results other people's votes bring. Each update is pushed into the document already playing: the
 * poll is never rebuilt, and only one `Impression` is ever reported.
 */
@OptIn(ExperimentalTestApi::class)
class PollA2uiTest : LiveryA2uiSurfaceTest() {
  private data class Option(val label: String, val votes: Int)

  /** The poll as the agent holds it: the data model is always rendered from this. */
  private val options = mutableListOf(Option("Kotlin", 66), Option("Swift", 35), Option("TypeScript", 27))
  private var chosen: Int? = null

  private fun pollData(): String {
    val total = options.sumOf { it.votes }
    val items = options.mapIndexed { i, o ->
      val share = o.votes.toDouble() / total
      """{"label":"${o.label}","share":$share,"result":"${Math.round(share * 100)}%","mark":"${if (i == chosen) "✓" else ""}"}"""
    }
    val summary = "$total votes" + if (chosen != null) " · you voted" else ""
    return """{"version":"v0.9","updateDataModel":{"surfaceId":"$SURFACE","path":"/poll","value":{
      "question":"Which language do you write your UI in?","summary":"$summary","options":[${items.joinToString(",")}]}}}"""
  }

  private fun components(wired: Boolean = true) =
    """
    {"version":"v0.9","updateComponents":{"surfaceId":"$SURFACE","components":[
      {"id":"root","component":"Column","children":["intro","poll"]},
      {"id":"intro","component":"Text","text":"Quick question before you go","variant":"h5"},
      {"id":"poll","component":"SocialPoll",
        "question":{"path":"/poll/question"},"summary":{"path":"/poll/summary"},"options":{"path":"/poll/options"}
        ${if (wired) ""","actions":{"vote":{"event":{"name":"vote","context":{"poll":"ui-language"}}}}""" else ""}}
    ]}}
    """.trimIndent()

  /** The agent's side: a `vote` event counts the vote and sends the new results. */
  private fun agentAnswersVotes() {
    val votes = outbound.filterIsInstance<A2uiClientEventMessage>().filter { it.type == "vote" }
    for (vote in votes.drop(answered)) {
      val i = (vote.context["option"] as String).toInt()
      options[i] = options[i].copy(votes = options[i].votes + 1)
      chosen = i
      send(pollData())
    }
    answered = votes.size
  }

  private var answered = 0

  @Test fun thePollIsTheContractsComponent() {
    val promo = catalog.components["SocialPoll"] as LiveryA2uiComponent
    assertEquals("social/poll@1", promo.contract.ref)
  }

  @Test fun aVoteGoesToTheAgentAndItsResultsUpdateTheSameDocument() = runComposeUiTest {
    send(create, pollData(), components())
    show()
    val before = capture("poll-open")

    onNodeWithText("Swift", useUnmergedTree = true).performClick()
    waitUntil(timeoutMillis = 5_000) { outbound.any { it is A2uiClientEventMessage } }
    val vote = outbound.filterIsInstance<A2uiClientEventMessage>().single()
    assertEquals("vote", vote.type)
    assertEquals("poll", vote.componentId)
    assertEquals("1", vote.context["option"], "the template's argument: Swift is option 1")
    assertEquals("ui-language", vote.context["poll"], "the agent's own context")
    assertTrue(events.any { it.type == TemplateEvent.Type.Action && it.action == "vote" && it.args == mapOf("option" to "1") }, "Livery's Action event, with the declared argument")

    agentAnswersVotes()
    waitForIdle()
    val voted = capture("poll-voted")
    assertFalse(sameImage(before, voted), "the new results and the ✓ draw")

    // Other people keep voting: the agent streams results into the same data model.
    options[0] = options[0].copy(votes = options[0].votes + 40)
    send(pollData())
    waitForIdle()
    val later = capture("poll-later")
    assertFalse(sameImage(voted, later), "the live results draw")

    assertEquals(1, events.count { it.type == TemplateEvent.Type.Impression }, "one document the whole time: updated, never rebuilt")
  }

  @Test fun aVoteTheAgentDidntWireGoesToTheApp() = runComposeUiTest {
    send(create, pollData(), components(wired = false))
    show()
    onNodeWithText("TypeScript", useUnmergedTree = true).performClick()
    waitUntil(timeoutMillis = 5_000) { appHandled.isNotEmpty() }
    assertEquals("app:vote" to mapOf("option" to "2"), appHandled.single(), "the app's handler for the kind, with the argument")
    assertTrue(outbound.none { it is A2uiClientEventMessage }, "nothing went to the agent")
  }

  @Test fun theDocumentDrawsAsManyOptionsAsTheDataHas() = runComposeUiTest {
    options.removeAt(2)
    send(create, pollData(), components())
    show()
    val two = capture("poll-two-options")
    options += Option("TypeScript", 27)
    send(pollData())
    waitForIdle()
    val three = capture("poll-three-options")
    assertTrue(three.height > two.height, "a third option's row appears: ${two.height} → ${three.height}")
  }
}
