package ee.schimke.livery.sessions.server

import ee.schimke.livery.sessions.Message
import ee.schimke.livery.sessions.SessionRelay
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The agent's side (SESSIONS.md, Wire format): one MCP endpoint for every session, configured once
 * and needing no header. The agent joins with a code (or asks for access), then passes the returned
 * `session` to every other tool. Drafts go to their kind's [SessionKind.submit].
 *
 * Stateless JSON-RPC: [handle] takes one message and returns its response, null for a
 * notification.
 */
class SessionMcp(private val server: SessionServer) {
  private val relay: SessionRelay
    get() = server.relay

  suspend fun handle(message: JsonObject, bearer: String = ""): JsonObject? {
    val id = message["id"] ?: return null
    fun error(code: Int, reason: String) = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      putJsonObject("error") {
        put("code", code)
        put("message", reason)
      }
    }
    fun result(value: JsonElement) = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      put("result", value)
    }
    fun tool(data: JsonObject, isError: Boolean = false) = result(toolResult(data, isError))
    fun failed(reason: String) = tool(buildJsonObject { put("error", reason) }, true)

    if (message["jsonrpc"] != JsonPrimitive("2.0")) return error(-32600, "Invalid request")
    val method = (message["method"] as? JsonPrimitive)?.contentOrNull ?: return error(-32600, "Invalid request")
    return when (method) {
      "initialize" ->
        result(
          buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
            putJsonObject("serverInfo") {
              put("name", server.text.serverName)
              put("version", "1")
            }
            put("instructions", server.text.instructions)
          }
        )
      "ping" -> result(JsonObject(emptyMap()))
      "tools/list" -> result(buildJsonObject { put("tools", tools()) })
      "tools/call" -> {
        val params = message["params"] as? JsonObject ?: return error(-32602, "Missing parameters")
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        fun arg(key: String) = (args[key] as? JsonPrimitive)?.contentOrNull
        val session = arg("session")?.takeIf { it.isNotBlank() } ?: bearer
        when (name) {
          "connect",
          "join_session" -> {
            val code = arg("code").orEmpty()
            relay.connect(code, arg("agentName"))?.let {
              return tool(linked(it))
            }
            val joined =
              relay.join(code, arg("agentName"))
                ?: return failed("Unknown or expired code. Ask the person for the code in their app.")
            tool(joined.json("Call get_session next."))
          }
          "await_request" -> {
            val since = (args["since"] as? JsonPrimitive)?.longOrNull ?: 0L
            val inbox =
              relay.links.awaitRequests(arg("link").orEmpty(), since)
                ?: return failed("This link was disconnected or expired. Ask the person to connect you again.")
            tool(
              buildJsonObject {
                put("cursor", inbox.cursor)
                putJsonArray("requests") {
                  inbox.requests.forEach { r ->
                    add(
                      buildJsonObject {
                        put("session", r.session)
                        put("kind", r.kind)
                        put("title", r.title)
                      }
                    )
                  }
                }
                put(
                  "next",
                  if (inbox.requests.isEmpty()) "Nothing yet; call await_request again with cursor."
                  else "Work each request in its session (get_session first), then call await_request again with cursor.",
                )
              }
            )
          }
          "request_access" -> {
            val pending =
              try {
                relay.requestAccess(arg("agentName"))
              } catch (_: IllegalStateException) {
                return failed("Too many pending requests; try again shortly.")
              }
            tool(
              buildJsonObject {
                put("code", pending.code)
                put("requestToken", pending.poll)
                put("expiresInSeconds", pending.expiresInSeconds)
                put(
                  "next",
                  "Tell the person to enter ${pending.code} ${server.text.whereToEnterCode}. Then call poll_access with requestToken.",
                )
              }
            )
          }
          "poll_access" -> {
            val polled =
              relay.pollAccess(arg("requestToken").orEmpty())
                ?: return failed("Unknown or expired request; call request_access again.")
            polled.link?.let {
              return tool(linked(it))
            }
            val joined = polled.joined
            if (joined == null)
              tool(
                buildJsonObject {
                  put("status", "pending")
                  put("next", "The person hasn't entered the code yet; call poll_access again.")
                }
              )
            else tool(joined.json("Approved. Call get_session next."))
          }
          "get_session" -> {
            val kind = relay.seen(session, "Reading the request")?.let(server.kinds::get) ?: return failed(GONE)
            val view = relay.agentView(session) ?: return failed(GONE)
            val draft = kind.read(session) ?: return failed(GONE)
            tool(
              buildJsonObject {
                draft.forEach { (k, v) -> put(k, v) }
                put("kind", kind.id)
                put("title", view.title)
                put("cursor", view.cursor)
                put("conversation", view.messages.json())
                view.decision?.let { put("decision", it) }
                put("personWatching", view.personWatching)
                put("expiresInSeconds", view.expiresInSeconds)
                put("next", nextStep(view))
                put("submitWith", kind.submitWith)
              }
            )
          }
          "set_status" -> {
            relay.seen(session, arg("status").orEmpty()) ?: return failed(GONE)
            tool(buildJsonObject { put("status", "shown") })
          }
          "send_message" -> {
            val text = arg("text").orEmpty()
            if (text.isBlank() || text.length > SessionRelay.MAX_TEXT)
              return failed("Send between 1 and ${SessionRelay.MAX_TEXT} characters.")
            if (!relay.agentMessage(session, text)) return failed(GONE)
            tool(buildJsonObject { put("status", "sent") })
          }
          "report_step" -> {
            val toolName = arg("tool").orEmpty()
            if (toolName.isBlank()) return failed("Name the tool or action the step was.")
            val ok = (args["ok"] as? JsonPrimitive)?.booleanOrNull ?: true
            if (!relay.agentStep(session, toolName, arg("arguments").orEmpty(), arg("output").orEmpty(), ok))
              return failed(GONE)
            tool(buildJsonObject { put("status", "shown") })
          }
          "submit_draft" -> {
            val kind = relay.seen(session, "Submitting a draft")?.let(server.kinds::get) ?: return failed(GONE)
            val revision =
              (args["revision"] as? JsonPrimitive)?.longOrNull
                ?: return failed("Pass the revision you read with get_session.")
            val summary = arg("summary").orEmpty().trim().take(SessionRelay.MAX_TEXT)
            when (val outcome = kind.submit(session, revision, JsonObject(args - "session" - "revision" - "summary"))) {
              DraftResult.Gone -> return failed(GONE)
              is DraftResult.Refused -> return failed(outcome.reason)
              is DraftResult.Accepted -> {
                if (summary.isNotBlank()) relay.agentMessage(session, summary, Message.SUMMARY)
                relay.seen(session, "Draft submitted")
                tool(
                  buildJsonObject {
                    outcome.details.forEach { (k, v) -> put(k, v) }
                    put("status", "submitted")
                    put("next", "The person sees the draft now. Call await_feedback to hear what they think.")
                  }
                )
              }
            }
          }
          "get_render" -> {
            relay.seen(session) ?: return failed(GONE)
            val (png, revision) =
              relay.render(session)
                ?: return tool(
                  buildJsonObject {
                    put("status", "none")
                    put("next", "The person's app hasn't drawn a draft yet; try after submit_draft.")
                  }
                )
            result(
              buildJsonObject {
                putJsonArray("content") {
                  add(
                    buildJsonObject {
                      put("type", "image")
                      put("data", Base64.getEncoder().encodeToString(png))
                      put("mimeType", "image/png")
                    }
                  )
                  add(
                    buildJsonObject {
                      put("type", "text")
                      put("text", "Revision $revision as the person's app draws it, with their own data.")
                    }
                  )
                }
                putJsonObject("structuredContent") { put("revision", revision) }
              }
            )
          }
          "await_feedback" -> {
            val since = (args["since"] as? JsonPrimitive)?.longOrNull ?: 0L
            val view = relay.awaitFeedback(session, since) ?: return failed(GONE)
            val feedback = view.messages.filter { it.from == Message.FROM_PERSON }
            tool(
              buildJsonObject {
                put("cursor", view.cursor)
                put("feedback", feedback.json())
                view.decision?.let { put("decision", it) }
                put("personWatching", view.personWatching)
                put(
                  "next",
                  when {
                    view.decision == SessionRelay.ACCEPTED -> "The person accepted the draft. You're done."
                    view.decision == SessionRelay.REJECTED && feedback.none { it.kind == Message.COMMENT } ->
                      "The person rejected the draft. Stop, or ask what they would like."
                    feedback.isEmpty() -> "No feedback yet; call await_feedback again with cursor."
                    else -> "Act on the feedback, submit_draft, then await_feedback with cursor."
                  },
                )
              }
            )
          }
          else -> {
            // A kind's own tool, looked up on the session's own kind: two kinds may share a name.
            val owners = server.kinds.values.filter { k -> k.tools.any { it.name == name } }
            if (owners.isEmpty()) return error(-32602, "Unknown tool")
            val kind = relay.seen(session)?.let(server.kinds::get) ?: return failed(GONE)
            val sessionTool =
              kind.tools.firstOrNull { it.name == name }
                ?: return failed("$name is for ${owners.joinToString(" and ") { it.id }} sessions only.")
            when (val outcome = sessionTool.call(session, JsonObject(args - "session"))) {
              is SessionToolResult.Done -> tool(outcome.data)
              is SessionToolResult.Failed -> failed(outcome.reason)
            }
          }
        }
      }
      else -> error(-32601, "Method not found")
    }
  }

  private fun tools(): JsonArray = buildJsonArray {
    BASE_TOOLS.forEach { add(it) }
    server.kinds.values.flatMap { it.tools }.distinctBy { it.name }.forEach { t ->
      add(
        toolDef(
          t.name,
          t.description,
          buildJsonObject {
            t.inputSchema.forEach { (k, v) -> if (k != "properties" && k != "required") put(k, v) }
            if ("type" !in t.inputSchema) put("type", "object")
            putJsonObject("properties") {
              put("session", buildJsonObject { put("type", "string") })
              (t.inputSchema["properties"] as? JsonObject)?.forEach { (k, v) -> put(k, v) }
            }
            putJsonArray("required") {
              add("session")
              (t.inputSchema["required"] as? JsonArray)?.forEach { add(it) }
            }
          },
        )
      )
    }
  }

  companion object {
    const val PROTOCOL_VERSION: String = "2025-03-26"
    private const val GONE = "This session ended or expired. Ask the person for a new code."

    /**
     * What an agent reading a session does now: the person can comment before any agent joins, so
     * comments may already be waiting for it (in the conversation, before its cursor).
     */
    fun nextStep(view: SessionRelay.AgentView): String {
      val lastAgent = view.messages.lastOrNull { it.fromAgent }?.seq ?: 0
      val waiting = view.messages.count { it.from == Message.FROM_PERSON && it.kind == Message.COMMENT && it.seq > lastAgent }
      return if (waiting > 0)
        "The person has already commented ($waiting in the conversation): act on those now and submit_draft, then await_feedback with cursor."
      else "Say hello with send_message, then await_feedback with cursor for the person's comments."
    }

    /** Messages as the agent and the person's app read them. */
    fun List<Message>.json(): JsonArray = buildJsonArray {
      forEach { m ->
        add(
          buildJsonObject {
            put("seq", m.seq)
            put("from", m.from)
            put("kind", m.kind)
            put("text", m.text)
            m.target?.let { put("target", it) }
            if (m.isStep) {
              m.tool?.let { put("tool", it) }
              m.arguments?.let { put("arguments", it) }
              put("ok", m.ok)
            }
          }
        )
      }
    }

    private fun linked(link: String) = buildJsonObject {
      put("status", "connected")
      put("link", link)
      put(
        "next",
        "Keep this link: it lasts while you use it (30 days idle at most). Call await_request with it now, and again whenever you are free, to receive the person's requests.",
      )
    }

    private fun SessionRelay.Joined.json(next: String) = buildJsonObject {
      put("status", "joined")
      put("session", session)
      put("kind", kind)
      put("title", title)
      put("next", "Pass session to every other tool. $next")
    }

    private fun toolResult(data: JsonObject, isError: Boolean) = buildJsonObject {
      putJsonArray("content") {
        add(
          buildJsonObject {
            put("type", "text")
            put("text", data.toString())
          }
        )
      }
      put("structuredContent", data)
      if (isError) put("isError", true)
    }

    private fun schema(vararg props: Pair<String, String>, required: List<String> = emptyList()) = buildJsonObject {
      put("type", "object")
      putJsonObject("properties") { props.forEach { (name, type) -> putJsonObject(name) { put("type", type) } } }
      if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it) } }
    }

    private fun toolDef(name: String, description: String, input: JsonObject) = buildJsonObject {
      put("name", name)
      put("description", description)
      put("inputSchema", input)
    }

    private val BASE_TOOLS: List<JsonObject> =
      listOf(
        toolDef(
          "connect",
          "Connect with the code the person gives you (like K7QM-4MD2). A setup code returns a lasting link for await_request; a code from one session returns that session.",
          schema("code" to "string", "agentName" to "string", required = listOf("code")),
        ),
        toolDef(
          "await_request",
          "Listen for the person's requests on your link: waits up to 25 s, returns new requests after since (0 at first), each with its session. Call it again with the returned cursor.",
          schema("link" to "string", "since" to "integer", required = listOf("link")),
        ),
        toolDef(
          "join_session",
          "The same as connect, kept for agents that learned this name first.",
          schema("code" to "string", "agentName" to "string", required = listOf("code")),
        ),
        toolDef(
          "request_access",
          "Ask to join when the person has no code: show them the returned code to enter in their app, then call poll_access.",
          schema("agentName" to "string"),
        ),
        toolDef(
          "poll_access",
          "Wait (up to 25 s) for the person to enter the request_access code. Returns a link or a session.",
          schema("requestToken" to "string", required = listOf("requestToken")),
        ),
        toolDef(
          "get_session",
          "Read the request, the current draft, its revision and the conversation so far.",
          schema("session" to "string", required = listOf("session")),
        ),
        toolDef(
          "set_status",
          "Show the person what you are doing, in a few words (for example: Drafting a compact layout).",
          schema("session" to "string", "status" to "string", required = listOf("session", "status")),
        ),
        toolDef(
          "send_message",
          "Say something to the person, such as a question or what you changed. They see it in the app.",
          schema("session" to "string", "text" to "string", required = listOf("session", "text")),
        ),
        toolDef(
          "report_step",
          "Show the person one step you took (a lookup, a check, a change), in words: tool, what you asked, what you got, and whether it worked.",
          buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
              listOf("session", "tool", "arguments", "output").forEach { putJsonObject(it) { put("type", "string") } }
              putJsonObject("ok") { put("type", "boolean") }
            }
            putJsonArray("required") {
              add("session")
              add("tool")
            }
          },
        ),
        toolDef(
          "submit_draft",
          "Submit a complete draft at the revision you read, as get_session's submitWith says, with a one-line summary. The person sees it at once and decides; you never apply it.",
          buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
              putJsonObject("session") { put("type", "string") }
              putJsonObject("revision") { put("type", "integer") }
              putJsonObject("summary") { put("type", "string") }
            }
            put("additionalProperties", true)
            putJsonArray("required") {
              add("session")
              add("revision")
            }
          },
        ),
        toolDef(
          "get_render",
          "See your latest draft exactly as the person's app draws it (a PNG, with their own data), once they have opened it.",
          schema("session" to "string", required = listOf("session")),
        ),
        toolDef(
          "await_feedback",
          "Wait up to 25 s for the person's comments or decision after since (0 at first). Call it again with the returned cursor until a decision arrives.",
          schema("session" to "string", "since" to "integer", required = listOf("session")),
        ),
      )
  }
}
