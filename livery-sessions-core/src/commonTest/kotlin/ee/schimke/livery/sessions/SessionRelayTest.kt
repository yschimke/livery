package ee.schimke.livery.sessions

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/** A store that keeps a revision per owner, as an app's kind would. */
private class FakeStore : DraftStore {
  val revisions = HashMap<String, Long>()
  val notes = ArrayList<String>()

  override fun revision(owner: String) = revisions[owner]

  override fun touch(owner: String) = revisions[owner]?.let { 3600L }

  override fun revoke(owner: String) = revisions.remove(owner) != null

  override fun note(owner: String, text: String) {
    notes += text
  }
}

/** Codes and tokens in a fixed order, so a test knows them. */
private class CountingEntropy : Entropy {
  private var n = 0
  private var t = 0

  override fun nextInt(bound: Int) = (n++ * 7) % bound

  override fun token() = "token-${t++}"
}

private const val EPOCH = 1_795_000_000_000L

class SessionRelayTest {
  private val store = FakeStore()

  private fun TestScope.relay() =
    // A wall clock, moving with virtual time: 0 means "never" to the relay.
    SessionRelay(mapOf("template" to store), CountingEntropy(), clock = { EPOCH + testScheduler.currentTime })

  private suspend fun SessionRelay.open(owner: String = "owner", agent: String = "agent"): String {
    store.revisions[owner] = 0
    return register("template", owner, agent, "The session card")!!
  }

  @Test
  fun aCodeLetsTheAgentInOnce() = runTest {
    val relay = relay()
    val code = relay.open()
    assertEquals(9, code.length)
    // However the person typed it.
    val joined = relay.join(code.lowercase().replace("-", " "), "Claude")
    assertEquals(SessionRelay.Joined("agent", "template", "The session card"), joined)
    assertNull(relay.join(code, "Another"))
    val state = relay.ownerState("owner")!!
    assertEquals("Claude", state.agent.name)
    assertTrue(state.agent.connected)
    assertNull(state.joinCode)
  }

  @Test
  fun thePersonWaitsForWhatTheAgentSays() = runTest {
    val relay = relay()
    relay.open()
    val before = relay.ownerState("owner")!!.version
    val waiting = async { relay.ownerState("owner", since = before) }
    delay(1_000)
    relay.agentMessage("agent", "Drafting a compact layout")
    val after = waiting.await()!!
    assertEquals("Drafting a compact layout", after.messages.last().text)
    // Nothing new: held for the longest wait, then the state as it is.
    val start = testScheduler.currentTime
    val idle = relay.ownerState("owner", since = after.version)!!
    assertEquals(SessionRelay.MAX_WAIT_MS, testScheduler.currentTime - start)
    assertEquals(after.messages, idle.messages)
  }

  @Test
  fun theAgentWaitsForThePersonNotForItself() = runTest {
    val relay = relay()
    relay.open()
    val view = relay.agentView("agent")!!
    val waiting = async { relay.awaitFeedback("agent", view.cursor) }
    delay(1_000)
    // The agent's own message and steps don't end its wait.
    relay.agentMessage("agent", "Which room?")
    relay.agentStep("agent", "find_entities", "soil", "1 found", ok = true)
    delay(1_000)
    relay.comment("owner", "Make it bigger", target = "Front door (card 2)")
    val feedback = waiting.await()!!
    assertEquals(
      listOf("Which room?", "Make it bigger"),
      feedback.messages.map { it.text },
      "steps are the person's view of the work, not the agent's",
    )
    assertEquals("Front door (card 2)", feedback.messages.last().target)
    assertEquals(listOf("Make it bigger"), store.notes)
  }

  @Test
  fun stepsShareTheSequenceAndAreBounded() = runTest {
    val relay = relay()
    relay.open()
    relay.comment("owner", "Add soil", null)
    relay.agentStep("agent", "find_entities", "soil", "x".repeat(2_000), ok = true)
    relay.agentMessage("agent", "Done")
    val messages = relay.ownerState("owner")!!.messages
    assertEquals(listOf(1L, 2L, 3L), messages.map { it.seq })
    val step = messages[1]
    assertTrue(step.isStep)
    assertEquals("find_entities", step.tool)
    assertEquals(SessionRelay.MAX_STEP_TEXT, step.text.length)
    repeat(SessionRelay.MAX_STEPS + 5) { relay.agentStep("agent", "home_overview", "", "3 areas", ok = true) }
    val after = relay.ownerState("owner")!!.messages
    assertEquals(SessionRelay.MAX_STEPS, relay.snapshot().sessions.single().messages.count { it.isStep })
    // The person's first comment outlives the agent's steps.
    assertEquals("Add soil", relay.snapshot().sessions.single().messages.first().text)
    assertTrue(after.size <= SessionRelay.OWNER_MESSAGES)
  }

  @Test
  fun theAgentAsksFirstAndThePersonLetsItIn() = runTest {
    val relay = relay()
    relay.open()
    val pending = relay.requestAccess("Codex")
    val polling = async { relay.pollAccess(pending.poll) }
    delay(2_000)
    assertEquals(SessionRelay.ClaimResult.Unknown, relay.claim("owner", "AAAA-AAAA"))
    assertEquals(SessionRelay.ClaimResult.Claimed, relay.claim("owner", pending.code))
    assertEquals(SessionRelay.Joined("agent", "template", "The session card"), polling.await()!!.joined)
    assertEquals("Codex", relay.ownerState("owner")!!.agent.name)
    assertEquals(0, relay.pendingRequests())
  }

  @Test
  fun claimingSpendsTheCodeThePersonWasShown() = runTest {
    val relay = relay()
    val shown = relay.open()
    val pending = relay.requestAccess("Codex")
    assertEquals(SessionRelay.ClaimResult.Claimed, relay.claim("owner", pending.code))
    // Another agent holding the first code doesn't get the same session.
    assertNull(relay.join(shown, "Someone else"))
    assertNull(relay.ownerState("owner")!!.joinCode)
    assertEquals("Codex", relay.ownerState("owner")!!.agent.name)
  }

  @Test
  fun aCommentAsTheAgentStartsWaitingIsNotLost() = runTest {
    val relay = relay()
    relay.open()
    relay.join(relay.newJoinCode("owner")!!, null)
    val cursor = relay.agentView("agent")!!.cursor
    // The comment lands in the same instant as the wait begins.
    val waiting = async { relay.awaitFeedback("agent", cursor) }
    val commenting = async { relay.comment("owner", "Now", null) }
    commenting.await()
    assertEquals(listOf("Now"), waiting.await()!!.messages.map { it.text })
    assertTrue(testScheduler.currentTime < SessionRelay.MAX_WAIT_MS)
  }

  @Test
  fun replacingALinkedAgentCutsTheOldOneOff() = runTest {
    val relay = relay()
    val created = relay.links.create()
    val first = relay.connect(created.code, "Claude")!!
    val stillWaiting = async { relay.links.awaitRequests(first, since = 0) }
    delay(1_000)
    val second = relay.connect(relay.links.newCode(created.owner)!!, "Codex")!!
    assertTrue(first != second, "the replacement gets a capability of its own")
    // The old agent's wait ends with nothing, and it can't listen again.
    assertNull(stillWaiting.await())
    assertNull(relay.links.awaitRequests(first, since = 0))
    store.revisions["owner"] = 0
    relay.register("template", "owner", "agent", "Speaker card", link = created.owner)
    assertEquals(listOf("agent"), relay.links.awaitRequests(second, since = 0)!!.requests.map { it.session })
    assertEquals("Codex", relay.links.state(created.owner)!!.name)
  }

  @Test
  fun wrongCodesShareOneBudget() = runTest {
    val relay = relay()
    val code = relay.open()
    repeat(SessionRelay.MAX_FAILURES_PER_MINUTE) { assertNull(relay.join("BBBB-BBBB", null)) }
    // Throttled: even the right code waits out the minute.
    assertNull(relay.join(code, null))
    delay(60_001)
    assertNotNull(relay.join(code, null))
  }

  @Test
  fun aSessionTheStoreDroppedIsGoneForBothSides() = runTest {
    val relay = relay()
    relay.open()
    relay.join(relay.newJoinCode("owner")!!, null)
    store.revisions.remove("owner")
    assertNull(relay.ownerState("owner"))
    assertNull(relay.agentView("agent"))
    assertFalse(relay.comment("owner", "Hello", null))
    assertEquals(0, relay.size)
  }

  @Test
  fun aStoreChangeWakesThePerson() = runTest {
    val relay = relay()
    relay.open()
    val before = relay.ownerState("owner")!!.version
    val waiting = async { relay.ownerState("owner", since = before) }
    delay(1_000)
    store.revisions["owner"] = 1
    // From under the store's own lock, as a store calls it.
    relay.changed("owner")
    assertEquals(1, waiting.await()!!.revision)
    assertEquals(1_000, testScheduler.currentTime)
  }

  @Test
  fun aLinkedAgentGetsTheRequestWithNoCode() = runTest {
    val relay = relay()
    val created = relay.links.create()
    val link = relay.connect(created.code, "Claude")!!
    assertTrue(relay.links.state(created.owner)!!.connected)
    val listening = async { relay.links.awaitRequests(link, since = 0) }
    delay(1_000)
    store.revisions["owner"] = 0
    assertNull(relay.register("template", "owner", "agent", "Speaker card", link = created.owner))
    val inbox = listening.await()!!
    assertEquals(listOf(SessionLinks.Delivery(1, "agent", "template", "Speaker card")), inbox.requests)
    val state = relay.ownerState("owner")!!
    assertTrue(state.linked)
    assertEquals("Claude", state.agent.name)
    assertEquals("Picking it up", state.agent.status)
  }

  @Test
  fun theRenderIsThePersonsPng() = runTest {
    val relay = relay()
    relay.open()
    val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    assertTrue(relay.putRender("owner", png, revision = 4))
    val (bytes, revision) = relay.render("agent")!!
    assertContentEquals(png, bytes)
    assertEquals(4, revision)
    assertFailsWith<IllegalArgumentException> { relay.putRender("owner", byteArrayOf(1, 2, 3), 5) }
  }

  @Test
  fun aRestartKeepsSessionsLinksAndUnusedCodes() = runTest {
    val relay = relay()
    val code = relay.open()
    relay.comment("owner", "Bigger title", null)
    relay.agentStep("agent", "submit_draft", "Bigger", "Draft submitted", ok = true)
    relay.links.create()
    val saved = Json.encodeToString(SessionRelay.Snapshot.serializer(), relay.snapshot())

    val restarted = relay()
    restarted.restore(Json.decodeFromString(SessionRelay.Snapshot.serializer(), saved))
    assertEquals(
      listOf("Bigger title", "Draft submitted"),
      restarted.ownerState("owner")!!.messages.map { it.text },
    )
    assertEquals(1, restarted.links.snapshot().size)
    // The code the person was shown still works.
    assertNotNull(restarted.join(code, null))
  }

  @Test
  fun restoringSkipsWhatTheStoresNoLongerHold() = runTest {
    val relay = relay()
    relay.open()
    val saved = relay.snapshot()
    store.revisions.clear()
    val restarted = relay()
    restarted.restore(saved)
    assertEquals(0, restarted.size)
  }
}
