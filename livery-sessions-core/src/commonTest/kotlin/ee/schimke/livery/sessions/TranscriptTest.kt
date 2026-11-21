package ee.schimke.livery.sessions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranscriptTest {
  private fun person(seq: Long, text: String) = Message(seq, Message.FROM_PERSON, Message.COMMENT, text)

  private fun step(seq: Long, tool: String) = Message(seq, Message.FROM_AGENT, Message.STEP, "ok", tool = tool)

  private fun reply(seq: Long, text: String) = Message(seq, Message.FROM_AGENT, Message.MESSAGE, text)

  @Test
  fun stepsBelongToTheTurnTheirReplyEnds() {
    val items =
      transcript(
        listOf(
          person(1, "Add soil"),
          step(2, "find_entities"),
          step(3, "submit_draft"),
          reply(4, "Done"),
          person(5, "Bigger"),
          step(6, "submit_draft"),
        ),
      )
    assertEquals(4, items.size)
    val first = (items[1] as TranscriptItem.Turn).turn
    assertEquals(listOf(2L, 3L), first.steps.map { it.seq })
    assertEquals("Done", first.reply?.text)
    // Still working: steps, no reply yet.
    val last = (items[3] as TranscriptItem.Turn).turn
    assertEquals(listOf(6L), last.steps.map { it.seq })
    assertNull(last.reply)
  }

  @Test
  fun aReplyWithNoStepsIsATurnAndInterruptedStepsEndTheirs() {
    val items = transcript(listOf(reply(1, "Hello"), step(2, "home_overview"), person(3, "Wait")))
    assertEquals(listOf(true, true, false), items.map { it is TranscriptItem.Turn })
    assertNull((items[1] as TranscriptItem.Turn).turn.reply)
  }

  @Test
  fun aDraftSummaryIsSaidNotAReply() {
    val summary = Message(3, Message.FROM_AGENT, Message.SUMMARY, "Bigger title")
    val items = transcript(listOf(step(2, "submit_draft"), summary))
    assertEquals(listOf(true, false), items.map { it is TranscriptItem.Turn })
  }

  @Test
  fun toolsReadAsWords() {
    assertEquals("Find entities", stepTitle(step(1, "find_entities")))
    assertEquals("Step", stepTitle(Message(1, Message.FROM_AGENT, Message.STEP, "")))
  }

  @Test
  fun codesReadAloud() {
    assertEquals("K7QM4MD2".let(Codes::normalize), Codes.normalize("k7qm-4md2"))
    assertNull(Codes.normalize("K7QM-4MD0"), "0 is never in a code")
    assertEquals("K7QM-4MD2", Codes.format("K7QM4MD2"))
    assertEquals("Agent", Codes.cleanName("<>"))
    assertEquals("Claude (Opus)", Codes.cleanName("Claude (Opus)"))
  }
}
