package ee.schimke.livery.sessions.server

import ee.schimke.livery.sessions.DraftStore
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A kind whose drafts are a line of text, refused when blank: an app's own kind, in miniature. */
private class NoteKind(override val id: String = "note") : SessionKind {
  @Serializable data class Note(val owner: String, val agent: String, val text: String, val revision: Long)

  private val notes = HashMap<String, Note>()
  private val tokens = AtomicInteger()
  var server: SessionServer? = null

  fun create(text: String): Note =
    Note("$id-owner-${tokens.incrementAndGet()}", "$id-agent-${tokens.incrementAndGet()}", text, 0).also { notes[it.owner] = it }

  private fun byAgent(agent: String) = notes.values.firstOrNull { it.agent == agent }

  override val store =
    object : DraftStore {
      override fun revision(owner: String) = notes[owner]?.revision

      override fun touch(owner: String) = notes[owner]?.let { 3600L }

      override fun revoke(owner: String) = notes.remove(owner) != null
    }

  override fun read(agent: String): JsonObject? =
    byAgent(agent)?.let {
      buildJsonObject {
        put("text", it.text)
        put("revision", it.revision)
      }
    }

  override fun submit(agent: String, revision: Long, draft: JsonObject): DraftResult {
    val note = byAgent(agent) ?: return DraftResult.Gone
    if (revision != note.revision) return DraftResult.Refused("Read revision ${note.revision} first")
    val text = (draft["text"] as? JsonPrimitive)?.content.orEmpty()
    if (text.isBlank()) return DraftResult.Refused("A note needs text")
    notes[note.owner] = note.copy(text = text, revision = revision + 1)
    server?.changed(note.owner)
    return DraftResult.Accepted(buildJsonObject { put("revision", revision + 1) })
  }

  override val submitWith = "submit_draft with revision and text"

  override val tools =
    listOf(
      object : SessionTool {
        override val name = "shout"
        override val description = "Upper-case the draft."
        override val inputSchema = buildJsonObject { put("type", "object") }

        override suspend fun call(agent: String, arguments: JsonObject): SessionToolResult {
          val note = byAgent(agent) ?: return SessionToolResult.Failed("gone")
          return SessionToolResult.Done(buildJsonObject { put("text", note.text.uppercase()) })
        }
      }
    )

  override val httpPath = "/notes/session"

  override fun save(): JsonElement = Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Note.serializer()), notes.values.toList())

  override fun restore(saved: JsonElement) {
    Json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Note.serializer()), saved).forEach { notes[it.owner] = it }
  }
}

class SessionServerTest {
  private fun server(vararg kinds: NoteKind) =
    SessionServer(kinds.toList(), maxWaitMs = 1_000).also { s -> kinds.forEach { it.server = s } }

  private suspend fun HttpClient.mcp(tool: String, arguments: JsonObject): JsonObject {
    val response =
      post("/api/agent/mcp") {
        setBody(
          buildJsonObject {
              put("jsonrpc", "2.0")
              put("id", 1)
              put("method", "tools/call")
              putJsonObject("params") {
                put("name", tool)
                put("arguments", arguments)
              }
            }
            .toString()
        )
      }
    return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("result").jsonObject
  }

  private fun JsonObject.data() = getValue("structuredContent").jsonObject

  private fun JsonObject.failed() = this["isError"]?.jsonPrimitive?.boolean == true

  @Test
  fun aPersonAndAnAgentWorkADraftToADecision() = testApplication {
    val notes = NoteKind()
    val server = server(notes)
    application { routing { sessionRoutes(server) } }
    val note = notes.create("Buy milk")
    val code = server.start("note", note.owner, note.agent, "Shopping")!!

    // The agent joins with the code and reads the draft.
    val joined = client.mcp("connect", buildJsonObject { put("code", code) }).data()
    assertEquals("note", joined.getValue("kind").jsonPrimitive.content)
    val session = joined.getValue("session").jsonPrimitive.content
    val read = client.mcp("get_session", buildJsonObject { put("session", session) }).data()
    assertEquals("Buy milk", read.getValue("text").jsonPrimitive.content)
    assertEquals("submit_draft with revision and text", read.getValue("submitWith").jsonPrimitive.content)

    // The kind's gate refuses a bad draft and keeps a good one.
    val bad = client.mcp("submit_draft", buildJsonObject { put("session", session); put("revision", 0); put("text", " ") })
    assertTrue(bad.failed())
    val good =
      client.mcp("submit_draft", buildJsonObject { put("session", session); put("revision", 0); put("text", "Buy oat milk"); put("summary", "Oat milk") })
    assertFalse(good.failed())
    assertEquals(1, good.data().getValue("revision").jsonPrimitive.long)
    client.mcp("report_step", buildJsonObject { put("session", session); put("tool", "find_shop"); put("output", "2 nearby") })

    // The person sees the new revision, the summary and the step.
    val owner =
      Json.parseToJsonElement(client.get("/api/agent/session") { bearerAuth(note.owner) }.bodyAsText()).jsonObject
    assertEquals(1, owner.getValue("revision").jsonPrimitive.long)
    val messages = owner.getValue("messages").jsonArray.map { it.jsonObject }
    assertEquals(listOf("summary", "tool"), messages.map { it.getValue("kind").jsonPrimitive.content })
    assertEquals("find_shop", messages[1].getValue("tool").jsonPrimitive.content)

    // A comment reaches the agent waiting for feedback; the step doesn't.
    client.post("/api/agent/session/comment") {
      bearerAuth(note.owner)
      setBody("""{"text":"And bread","target":"Line 1"}""")
    }
    val feedback = client.mcp("await_feedback", buildJsonObject { put("session", session) }).data()
    val said = feedback.getValue("feedback").jsonArray.map { it.jsonObject }
    assertEquals(listOf("And bread"), said.map { it.getValue("text").jsonPrimitive.content })
    assertEquals("Line 1", said.single().getValue("target").jsonPrimitive.content)

    // The decision ends the loop.
    client.post("/api/agent/session/decision") {
      bearerAuth(note.owner)
      setBody("""{"decision":"accepted"}""")
    }
    val cursor = feedback.getValue("cursor").jsonPrimitive.long
    val done = client.mcp("await_feedback", buildJsonObject { put("session", session); put("since", cursor) }).data()
    assertEquals("accepted", done.getValue("decision").jsonPrimitive.content)
  }

  @Test
  fun aKindsOwnToolIsOnlyForItsSessions() = testApplication {
    val notes = NoteKind()
    val other = NoteKind("other")
    val server = server(notes, other)
    application { routing { sessionRoutes(server) } }
    val note = notes.create("hello")
    server.start("note", note.owner, note.agent, "Note")
    val elsewhere = other.create("x")
    server.start("other", elsewhere.owner, elsewhere.agent, "Other")
    val shouted = client.mcp("shout", buildJsonObject { put("session", note.agent) })
    assertEquals("HELLO", shouted.data().getValue("text").jsonPrimitive.content, shouted.toString())
    // Both kinds name a tool `shout`: each session gets its own kind's.
    assertEquals("X", client.mcp("shout", buildJsonObject { put("session", elsewhere.agent) }).data().getValue("text").jsonPrimitive.content)
  }

  @Test
  fun agentsAreNeverServedToABrowserPage() = testApplication {
    val notes = NoteKind()
    val server = server(notes)
    application { routing { sessionRoutes(server) } }
    val note = notes.create("x")
    val code = server.start("note", note.owner, note.agent, "Note")!!
    val fromPage =
      client.post("/api/agent/join") {
        header(HttpHeaders.Origin, "https://example.com")
        setBody("""{"code":"$code"}""")
      }
    assertEquals(HttpStatusCode.Forbidden, fromPage.status)
    val joined = Json.parseToJsonElement(client.post("/api/agent/join") { setBody("""{"code":"$code"}""") }.bodyAsText()).jsonObject
    assertEquals("/notes/session", joined.getValue("http").jsonPrimitive.content)
  }

  @Test
  fun aRestartKeepsSessionsAndTheKindsOwnDrafts(): Unit = runBlocking {
    val file = Files.createTempDirectory("sessions").resolve("sessions.json").toFile()
    val notes = NoteKind()
    val server = server(notes)
    val note = notes.create("Buy milk")
    val code = server.start("note", note.owner, note.agent, "Shopping")!!
    server.relay.comment(note.owner, "Hurry", null)
    SessionFile(file, server).save()

    val restoredNotes = NoteKind()
    val restarted = server(restoredNotes)
    SessionFile(file, restarted).load()
    val state = assertNotNull(restarted.relay.ownerState(note.owner))
    assertEquals(listOf("Hurry"), state.messages.map { it.text })
    assertEquals("Buy milk", restoredNotes.read(note.agent)!!.getValue("text").jsonPrimitive.content)
    assertNotNull(restarted.relay.join(code, null), "the code the person was shown still works")
  }

  @Test
  fun anOversizedBodyIsRefused() = testApplication {
    val server = server(NoteKind())
    application { routing { sessionRoutes(server) } }
    val big = "{\"code\":\"" + "A".repeat(20_000) + "\"}"
    assertEquals(HttpStatusCode.BadRequest, client.post("/api/agent/join") { setBody(big) }.status)
  }

  @Test
  fun theSaveFileIsTheServersAlone(): Unit = runBlocking {
    val file = Files.createTempDirectory("sessions").resolve("sessions.json").toFile()
    val server = server(NoteKind())
    SessionFile(file, server).save()
    if ("posix" in file.toPath().fileSystem.supportedFileAttributeViews())
      assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
  }

  @Test
  fun anUnreadableFileStartsEmpty(): Unit = runBlocking {
    val file = Files.createTempDirectory("sessions").resolve("sessions.json").toFile().apply { writeText("{not json") }
    val server = server(NoteKind())
    SessionFile(file, server).load()
    assertNull(server.relay.ownerState("anyone"))
    assertEquals(0, server.relay.size)
  }
}
