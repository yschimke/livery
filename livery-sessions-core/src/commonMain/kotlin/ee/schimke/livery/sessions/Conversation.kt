package ee.schimke.livery.sessions

import kotlinx.serialization.Serializable

/**
 * One line of a session's conversation, in one sequence shared by everything said and done:
 * - from the person ([FROM_PERSON]): a [COMMENT], optionally about a [target] (a card on the
 *   preview, named by the app's [TargetLabeler]), or a [DECISION];
 * - from the agent ([FROM_AGENT]): a [MESSAGE], a draft's [SUMMARY], or a [STEP], a tool it called:
 *   [tool] names it, [arguments] says what it asked, [text] what it got, and [ok] whether it worked.
 *   Steps are for the person to watch the agent work; an agent's own context leaves them out.
 */
@Serializable
data class Message(
  val seq: Long,
  val from: String,
  val kind: String,
  val text: String,
  val target: String? = null,
  val at: Long = 0,
  val tool: String? = null,
  val arguments: String? = null,
  val ok: Boolean = true,
) {
  val fromAgent: Boolean
    get() = from == FROM_AGENT

  val isStep: Boolean
    get() = kind == STEP

  companion object {
    const val FROM_PERSON = "person"
    const val FROM_AGENT = "agent"

    const val COMMENT = "comment"
    const val DECISION = "decision"
    const val MESSAGE = "message"
    const val SUMMARY = "summary"
    const val STEP = "tool"
  }
}

/** How the app names what a comment is about: "Living room temperature (view 1, section 2, card 2)". */
fun interface TargetLabeler {
  /** [id] is the app's own id for the thing, from its preview; null when it can't name it. */
  fun label(id: String): String?
}

/** One turn of the agent: the [steps] it took, then its [reply] (null while it's still working). */
data class AgentTurn(val steps: List<Message>, val reply: Message?)

/** A line of a transcript: something said (by the person, or a decision or summary) or a turn. */
sealed interface TranscriptItem {
  data class Said(val message: Message) : TranscriptItem

  data class Turn(val turn: AgentTurn) : TranscriptItem
}

/**
 * [messages] (in sequence order) as transcript lines: the agent's steps up to its next message are
 * one turn, with that message as its reply. Steps something else interrupts end a turn with no
 * reply; trailing steps are the turn in progress.
 */
fun transcript(messages: List<Message>): List<TranscriptItem> {
  val items = mutableListOf<TranscriptItem>()
  var steps = mutableListOf<Message>()
  fun endTurn(reply: Message?) {
    if (steps.isNotEmpty() || reply != null) items += TranscriptItem.Turn(AgentTurn(steps, reply))
    steps = mutableListOf()
  }
  for (m in messages) {
    when {
      m.isStep -> steps += m
      m.fromAgent && m.kind == Message.MESSAGE -> endTurn(m)
      else -> {
        if (steps.isNotEmpty()) endTurn(null)
        items += TranscriptItem.Said(m)
      }
    }
  }
  if (steps.isNotEmpty()) endTurn(null)
  return items
}

/** A tool's name as a person reads it: `find_entities` is "Find entities". */
fun stepTitle(step: Message): String =
  step.tool.orEmpty().replace('_', ' ').replaceFirstChar { it.uppercase() }.ifBlank { "Step" }
