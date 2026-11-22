package ee.schimke.livery.sessions.server

import ee.schimke.livery.sessions.DraftStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One kind of agent session the server relays (SESSIONS.md, Model): Livery's `template`, or an
 * app's own (a screen's layout, an app's config). The relay keeps codes, presence, the conversation
 * and the decision; a kind keeps its drafts and decides what a valid one is.
 *
 * A kind's [store] calls [SessionServer.changed] when a draft arrives, so a waiting person sees it
 * at once.
 */
interface SessionKind {
  /** The wire name (`template`, `dashboard`): in `kind` fields, deliveries and saved sessions. */
  val id: String

  /** The kind's drafts and how long a session lives. */
  val store: DraftStore

  /**
   * What `get_session` shows the agent besides the conversation: the request, the current draft
   * and its `revision`. Null when the store no longer holds the session for [agent].
   */
  fun read(agent: String): JsonObject?

  /**
   * Check a draft the agent submitted at [revision] (the tool's arguments: everything but
   * `session` and `revision`; a one-line `summary` among them, which the server also posts to the
   * conversation when the draft is accepted) and keep it when it passes. A refusal says why, in words
   * the agent can act on.
   */
  fun submit(agent: String, revision: Long, draft: JsonObject): DraftResult

  /** How to call `submit_draft` for this kind, for `get_session`'s `submitWith`. */
  val submitWith: String
    get() = "submit_draft with revision and the complete draft"

  /**
   * Tools of this kind's own beside the shared ones (attaching an image to a draft). Kinds may share
   * a tool's name only with the same description and schema: `tools/list` is one list, and each
   * session's call goes to its own kind's implementation.
   */
  val tools: List<SessionTool>
    get() = emptyList()

  /** Where an agent without MCP reads and posts drafts over plain HTTP, for `join`'s answer. */
  val httpPath: String?
    get() = null

  /** Called once by the [SessionServer] this kind is registered with: where to report [SessionServer.changed]. */
  fun attached(server: SessionServer) {}

  /** The store's sessions to save across a restart, and their restore; null keeps nothing. */
  fun save(): JsonElement? = null

  fun restore(saved: JsonElement) {}
}

sealed interface DraftResult {
  /** Kept. [details] go back to the agent with the result (a new revision, warnings). */
  data class Accepted(val details: JsonObject = JsonObject(emptyMap())) : DraftResult

  data class Refused(val reason: String) : DraftResult

  /** The store no longer holds the session. */
  data object Gone : DraftResult
}

/** A kind's own MCP tool: offered in `tools/list` and called with the session's agent capability. */
interface SessionTool {
  val name: String
  val description: String
  /** The JSON schema of the arguments; `session` is added to it. */
  val inputSchema: JsonObject

  /** The tool's structured result, or a [DraftResult.Refused]-style error through [SessionToolResult]. */
  suspend fun call(agent: String, arguments: JsonObject): SessionToolResult
}

sealed interface SessionToolResult {
  /**
   * The tool's [data], and [images] for the agent to look at (a draft drawn with sample values):
   * MCP image content, each followed by its caption, before [data] as text and structured content.
   */
  data class Done(val data: JsonObject, val images: List<SessionImage>) : SessionToolResult {
    constructor(data: JsonObject) : this(data, emptyList())
  }

  data class Failed(val reason: String) : SessionToolResult
}

/** An image a [SessionTool] returns: PNG, JPEG or WebP [bytes], and what it shows in a few words. */
class SessionImage(val bytes: ByteArray, val mimeType: String = "image/png", val caption: String? = null) {
  init {
    require(mimeType in MIME_TYPES) { "An image is PNG, JPEG or WebP" }
    require(bytes.size in 1..MAX_BYTES) { "An image is at most $MAX_BYTES bytes" }
  }

  companion object {
    /** As large as a render the person's app uploads (`SessionRelay.MAX_RENDER_BYTES`). */
    const val MAX_BYTES: Int = ee.schimke.livery.sessions.SessionRelay.MAX_RENDER_BYTES
    val MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp")
  }
}
