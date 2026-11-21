package ee.schimke.livery.sessions.server

import ee.schimke.livery.sessions.Entropy
import ee.schimke.livery.sessions.SecureEntropy
import ee.schimke.livery.sessions.SessionRelay

/**
 * The agent session server (SESSIONS.md): the relay around the app's [kinds], with what the agent
 * is told in [text]. Mount it with [sessionRoutes]; keep it across restarts with [SessionFile].
 */
class SessionServer(
  kinds: List<SessionKind>,
  val text: SessionText = SessionText(),
  clock: () -> Long = System::currentTimeMillis,
  entropy: Entropy = SecureEntropy(),
  maxWaitMs: Long = SessionRelay.MAX_WAIT_MS,
) {
  val kinds: Map<String, SessionKind> = kinds.associateBy { it.id }

  init {
    require(this.kinds.size == kinds.size) { "Two kinds share an id" }
    // tools/list is one list: a name two kinds share must mean the same tool to an agent.
    kinds.flatMap { k -> k.tools.map { k.id to it } }.groupBy { it.second.name }.forEach { (name, owners) ->
      val shapes = owners.map { (_, t) -> t.description to t.inputSchema }.distinct()
      require(shapes.size == 1) { "Kinds ${owners.map { it.first }} define tool $name differently: give one its own name" }
    }
  }

  val relay: SessionRelay =
    SessionRelay(this.kinds.mapValues { it.value.store }, entropy, clock, maxWaitMs)

  init {
    kinds.forEach { it.attached(this) }
  }

  /** A kind's draft for [owner] changed: call from its store, under its own lock if need be. */
  fun changed(owner: String) = relay.changed(owner)

  /**
   * Start a session [kind]'s store just created (its [owner] and [agent] capabilities). With the
   * person's [link] it goes straight to their agent and returns null; otherwise the code to show.
   */
  suspend fun start(kind: String, owner: String, agent: String, title: String, link: String? = null): String? =
    relay.register(kind, owner, agent, title, link)
}

/**
 * What the agent and the person are told, in the app's words. The defaults name no app; an app
 * says where its codes are entered ("in Settings, Connect an agent").
 */
data class SessionText(
  val serverName: String = "livery-agent-sessions",
  /** MCP `initialize` instructions: how an agent works a session from connecting to a decision. */
  val instructions: String = DEFAULT_INSTRUCTIONS,
  /** Where the person enters a code an agent shows them, for `request_access`'s answer. */
  val whereToEnterCode: String = "in the app, where it asks for a code from your agent",
) {
  companion object {
    const val DEFAULT_INSTRUCTIONS: String =
      "You edit something with a person who watches in their app. Connect once: call connect " +
        "with the code the person gives you, and keep the link it returns for later conversations. " +
        "If they have no code, call request_access, tell them its code, and call poll_access " +
        "until it returns a link. Then listen: call await_request with the link in a loop (it waits " +
        "up to 25 s). Each request brings a session: get_session, set_status while you work, " +
        "report_step for each thing you look up or change, submit_draft with the revision you read, " +
        "get_render to see it as the person does, and await_feedback in a loop passing back its " +
        "cursor, acting on each comment (the person may comment before you join: act first on " +
        "comments already in get_session's conversation) until a decision arrives. Then go back " +
        "to await_request. send_message asks the person something. You never apply a draft; the " +
        "person does."
  }
}
