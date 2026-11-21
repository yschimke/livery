package ee.schimke.livery.sessions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/**
 * The live side of every agent session (SESSIONS.md): how an agent joins without a pasted
 * capability, who is connected, the conversation between the person and the agent (with the
 * agent's steps), the person's decision, and the render the person's app shares so the agent sees
 * what they see. Both sides connect out and long-poll it, for at most [maxWaitMs].
 *
 * The drafts stay in each kind's [DraftStore], which also decides how long a session lives; this
 * only adds the relay around them. Nothing here holds a backend credential, and an agent can never
 * apply a draft.
 *
 * Every function is safe to call from any coroutine. [changed] takes no lock, so a store may call
 * it from under its own.
 */
class SessionRelay(
  /** The stores by kind (`template`, an app's own kinds). */
  private val stores: Map<String, DraftStore>,
  private val entropy: Entropy,
  private val clock: () -> Long,
  /** The longest a long-poll is held: under common reverse-proxy idle timeouts. */
  val maxWaitMs: Long = MAX_WAIT_MS,
) {
  data class Presence(
    val joined: Boolean,
    val connected: Boolean,
    val name: String,
    val status: String,
    val lastSeenMs: Long?,
  )

  /** What the person's app shows: everything but the draft itself, which its store serves. */
  data class OwnerState(
    val version: Long,
    val kind: String,
    val title: String,
    val revision: Long,
    val expiresInSeconds: Long,
    val joinCode: String?,
    val joinCodeExpiresInSeconds: Long,
    val agent: Presence,
    val messages: List<Message>,
    val decision: String?,
    /** Sent to the person's linked agent: no code to hand over. */
    val linked: Boolean = false,
  )

  data class AgentView(
    val kind: String,
    val title: String,
    /** What the person said and decided, and the agent's own messages; never its steps. */
    val messages: List<Message>,
    val cursor: Long,
    val decision: String?,
    val personWatching: Boolean,
    val expiresInSeconds: Long,
  )

  data class Joined(val session: String, val kind: String, val title: String)

  data class Pending(val code: String, val poll: String, val expiresInSeconds: Long)

  /** What the person let the agent into: one session, or (from setup) a lasting link. */
  data class PollResult(val joined: Joined?, val link: String? = null)

  enum class ClaimResult {
    Claimed,
    Unknown,
    Throttled,
    Gone,
  }

  private inner class Entry(
    val kind: String,
    val owner: String,
    val agent: String,
    val title: String,
  ) {
    val version = MutableStateFlow(1L)
    var joinCode: String? = null
    var joinCodeExpires = 0L
    var agentName = "Agent"
    var agentStatus = ""
    var agentSeen = 0L
    var personSeen = 0L
    var seq = 0L
    val messages = ArrayList<Message>()
    var decision: String? = null
    /** The person's link (its owner capability) this session was delivered through. */
    var link: String? = null
    /** The latest draft as the person's app drew it (PNG), and the revision it shows. */
    var render: ByteArray? = null
    var renderRevision = 0L

    val store: DraftStore
      get() = stores.getValue(kind)

    fun bump() {
      version.update { it + 1 }
      changeCount.update { it + 1 }
    }
  }

  private inner class Request(val code: String, val poll: String, val name: String, val expires: Long) {
    var entry: Entry? = null
    /** Set instead of [entry] when the person entered the code for their agent link. */
    var link: String? = null
    val done = MutableStateFlow(false)
  }

  private val changeCount = MutableStateFlow(0L)

  /** Bumped on every change to any session or link: when to save ([snapshot]) again. */
  val changes: StateFlow<Long>
    get() = changeCount

  /** People's lasting connections to their agents: requests go to them without a code. */
  val links: SessionLinks = SessionLinks(entropy, clock, maxWaitMs) { changeCount.update { it + 1 } }

  private val lock = Mutex()
  // Read without the lock by [changed]: replaced whole, never mutated in place.
  private val byOwner = MutableStateFlow<Map<String, Entry>>(emptyMap())
  private val byAgent = MutableStateFlow<Map<String, Entry>>(emptyMap())
  private val codes = HashMap<String, Entry>()
  private val requests = HashMap<String, Request>()
  private val pollers = HashMap<String, Request>()
  private val failures = ArrayDeque<Long>()

  /** A store's draft for [owner] changed: the person (and a waiting agent) see it at once. */
  fun changed(owner: String) {
    byOwner.value[owner]?.bump()
  }

  /**
   * Start relaying a session [kind]'s store just created. With the person's connected [link] it
   * goes straight to their agent and needs no code (null); otherwise it returns the code to hand
   * over.
   */
  suspend fun register(
    kind: String,
    owner: String,
    agent: String,
    title: String,
    link: String? = null,
  ): String? {
    require(kind in stores) { "No store for $kind sessions" }
    prune()
    val entry = Entry(kind, owner, agent, title.take(MAX_TITLE))
    index(entry)
    val linkedTo = link?.let { links.deliver(it, agent, kind, entry.title) }
    return lock.withLock {
      if (linkedTo != null) {
        entry.link = link
        entry.agentName = linkedTo
        null
      } else issueCode(entry)
    }
      .also { entry.bump() }
  }

  // ---- The person's side (owner capability) ----

  fun isOwner(token: String): Boolean =
    token.isNotEmpty() && byOwner.value[token]?.let(::alive) == true

  /** The state now, or once it changes past [since] (up to [maxWaitMs]); null when gone. */
  suspend fun ownerState(owner: String, since: Long? = null, waitMs: Long = maxWaitMs): OwnerState? {
    var entry = byOwner.value[owner] ?: return null
    if (!alive(entry)) return forget(entry)
    if (since != null && entry.version.value <= since) {
      // Mark the person present before waiting, so a joining agent sees them watching.
      lock.withLock { entry.personSeen = clock() }
      withTimeoutOrNull(waitMs.coerceIn(0, maxWaitMs)) { entry.version.first { it > since } }
      entry = byOwner.value[owner] ?: return null
      if (!alive(entry)) return forget(entry)
    }
    val left = entry.store.touch(owner) ?: return forget(entry)
    val revision = entry.store.revision(owner) ?: return forget(entry)
    val linkListening = entry.link?.takeIf { entry.agentSeen == 0L }?.let { links.listening(it) }
    return lock.withLock {
      val now = clock()
      entry.personSeen = now
      if (entry.joinCodeExpires <= now) dropCode(entry)
      OwnerState(
        version = entry.version.value,
        kind = entry.kind,
        title = entry.title,
        revision = revision,
        expiresInSeconds = left,
        joinCode = entry.joinCode,
        joinCodeExpiresInSeconds = ((entry.joinCodeExpires - now) / 1000).coerceAtLeast(0),
        agent = presence(entry, now, linkListening),
        messages = entry.messages.takeLast(OWNER_MESSAGES),
        decision = entry.decision,
        linked = entry.link != null,
      )
    }
  }

  /** A fresh join code, replacing any unused one. */
  suspend fun newJoinCode(owner: String): String? {
    val entry = byOwner.value[owner]?.takeIf(::alive) ?: return null
    entry.store.touch(owner)
    return lock.withLock { issueCode(entry) }.also { entry.bump() }
  }

  /** Let the agent that showed the person [code] (from `request_access`) into this session. */
  suspend fun claim(owner: String, code: String): ClaimResult {
    val entry = byOwner.value[owner]?.takeIf(::alive) ?: return ClaimResult.Gone
    val normalized = Codes.normalize(code) ?: return ClaimResult.Unknown
    val request =
      lock.withLock {
        expireRequests()
        if (throttled()) return ClaimResult.Throttled
        val found = requests.remove(normalized)
        if (found == null) {
          failures.addLast(clock())
          return ClaimResult.Unknown
        }
        found.entry = entry
        // The code the person was shown is spent too: the agent they let in is the only one.
        dropCode(entry)
        entry.agentName = found.name
        entry.agentSeen = clock()
        entry.agentStatus = "Connected"
        found
      }
    request.done.value = true
    entry.bump()
    return ClaimResult.Claimed
  }

  /** Let the agent that showed the person [code] (from `request_access`) into their [link]. */
  suspend fun claimLink(link: String, code: String): ClaimResult {
    if (!links.isOwner(link)) return ClaimResult.Gone
    val normalized = Codes.normalize(code) ?: return ClaimResult.Unknown
    val request =
      lock.withLock {
        expireRequests()
        if (throttled()) return ClaimResult.Throttled
        requests.remove(normalized)
          ?: run {
            failures.addLast(clock())
            return ClaimResult.Unknown
          }
      }
    val agent = links.attachAgent(link, request.name) ?: return ClaimResult.Gone
    lock.withLock { request.link = agent }
    request.done.value = true
    // After the grant is recorded: the link's own change came before it.
    changeCount.update { it + 1 }
    return ClaimResult.Claimed
  }

  /** The person's app shares the draft as it drew it, so the agent sees exactly that. */
  suspend fun putRender(owner: String, png: ByteArray, revision: Long): Boolean {
    val entry = byOwner.value[owner]?.takeIf(::alive) ?: return false
    require(png.size in 1..MAX_RENDER_BYTES && isPng(png)) { "A render is a PNG of at most $MAX_RENDER_BYTES bytes" }
    lock.withLock {
      entry.render = png.copyOf()
      entry.renderRevision = revision
    }
    entry.bump()
    return true
  }

  /** The latest render for the agent: PNG bytes and the draft revision they show. */
  suspend fun render(agent: String): Pair<ByteArray, Long>? {
    val entry = byAgent.value[agent]?.takeIf(::alive) ?: return null
    return lock.withLock { entry.render?.let { it.copyOf() to entry.renderRevision } }
  }

  suspend fun comment(owner: String, text: String, target: String?): Boolean {
    val entry = byOwner.value[owner]?.takeIf(::alive) ?: return false
    require(text.isNotBlank() && text.length <= MAX_TEXT && (target?.length ?: 0) <= MAX_TARGET)
    // Agents that read the store directly see the comment as a note.
    entry.store.note(owner, text)
    append(entry, Message.FROM_PERSON, Message.COMMENT, text.trim(), target?.trim()?.ifEmpty { null })
    return true
  }

  suspend fun decide(owner: String, accepted: Boolean, text: String? = null): Boolean {
    val entry = byOwner.value[owner]?.takeIf(::alive) ?: return false
    require((text?.length ?: 0) <= MAX_TEXT)
    val decision = if (accepted) ACCEPTED else REJECTED
    lock.withLock { entry.decision = decision }
    append(entry, Message.FROM_PERSON, Message.DECISION, text?.trim()?.ifEmpty { null } ?: decision, null)
    return true
  }

  /** Revoke from the owner side: the store forgets the session and both sides see it end. */
  suspend fun revoke(owner: String): Boolean {
    val entry = byOwner.value[owner] ?: return false
    val removed = entry.store.revoke(owner)
    forget<Unit>(entry)
    return removed
  }

  // ---- The agent's side (agent capability) ----

  fun kindOf(agent: String): String? = byAgent.value[agent]?.takeIf(::alive)?.kind

  /** Redeem the code the person's app shows. Single use; returns the agent capability. */
  suspend fun join(code: String, agentName: String?): Joined? {
    val normalized = Codes.normalize(code) ?: return null
    val entry =
      lock.withLock {
        if (throttled()) return null
        val found = codes[normalized]
        if (found == null || found.joinCodeExpires <= clock()) {
          failures.addLast(clock())
          return null
        }
        dropCode(found)
        found.agentName = Codes.cleanName(agentName)
        found.agentSeen = clock()
        found.agentStatus = "Connected"
        found
      }
    if (!alive(entry)) return forget(entry)
    entry.bump()
    return Joined(entry.agent, entry.kind, entry.title)
  }

  /**
   * Redeem the code the person gave their agent in setup: the agent's link capability, or null when
   * [code] isn't an unused link code.
   */
  suspend fun connect(code: String, agentName: String?): String? {
    val normalized = Codes.normalize(code) ?: return null
    if (lock.withLock { throttled() }) return null
    // A miss isn't counted here: the caller tries the code as a session's next, which counts it.
    return links.connect(normalized, agentName)
  }

  /** An agent asks first: it shows the person [Pending.code] and polls with [Pending.poll]. */
  suspend fun requestAccess(agentName: String?): Pending =
    lock.withLock {
      expireRequests()
      check(requests.size < MAX_REQUESTS) { "Too many pending agent requests" }
      var code: String
      do code = Codes.random(entropy) while (code in requests || code in codes)
      val request = Request(code, entropy.token(), Codes.cleanName(agentName), clock() + REQUEST_MS)
      requests[code] = request
      pollers[request.poll] = request
      Pending(Codes.format(code), request.poll, REQUEST_MS / 1000)
    }
      .also { changeCount.update { it + 1 } }

  /** Waits for the person to enter the code; null once the request is unknown or expired. */
  suspend fun pollAccess(poll: String, waitMs: Long = maxWaitMs): PollResult? {
    val request = lock.withLock { pollers[poll] } ?: return null
    if (lock.withLock { request.entry == null && request.link == null }) {
      if (request.expires <= clock()) {
        lock.withLock {
          pollers.remove(poll)
          requests.remove(request.code)
        }
        return null
      }
      withTimeoutOrNull(waitMs.coerceIn(0, maxWaitMs)) { request.done.first { it } }
    }
    val (link, claimed) =
      lock.withLock {
        val link = request.link
        val claimed = request.entry
        if (link != null || claimed != null) pollers.remove(poll)
        link to claimed
      }
    // Collected: a saved copy of the grant must not come back after a restart.
    if (link != null || claimed != null) changeCount.update { it + 1 }
    if (link != null) return PollResult(null, link)
    val entry = claimed ?: return PollResult(null)
    if (!alive(entry)) return null
    return PollResult(Joined(entry.agent, entry.kind, entry.title))
  }

  /** Record that the agent is active, optionally with what it's doing. Null when gone. */
  suspend fun seen(agent: String, status: String? = null): String? {
    val entry = byAgent.value[agent] ?: return null
    if (!alive(entry)) return forget(entry)
    entry.store.touch(entry.owner)
    lock.withLock {
      entry.agentSeen = clock()
      if (status != null) entry.agentStatus = status.trim().take(MAX_STATUS)
    }
    entry.bump()
    return entry.kind
  }

  /** Something the agent says to the person, or ([Message.SUMMARY]) what a draft changed. */
  suspend fun agentMessage(agent: String, text: String, kind: String = Message.MESSAGE): Boolean {
    require(kind == Message.MESSAGE || kind == Message.SUMMARY) { "Not an agent message: $kind" }
    val entry = byAgent.value[agent]?.takeIf(::alive) ?: return false
    require(text.isNotBlank() && text.length <= MAX_TEXT)
    seen(agent)
    append(entry, Message.FROM_AGENT, kind, text.trim(), null)
    return true
  }

  /**
   * One step the agent took (a tool it called, what it asked and what it got, in words), in the
   * conversation's sequence, so the person sees the agent work between replies. Long text is
   * clipped to [MAX_STEP_TEXT]; steps past [MAX_STEPS] drop the oldest.
   */
  suspend fun agentStep(agent: String, tool: String, arguments: String, output: String, ok: Boolean): Boolean {
    val entry = byAgent.value[agent]?.takeIf(::alive) ?: return false
    require(tool.isNotBlank())
    seen(agent)
    lock.withLock {
      entry.seq++
      entry.messages +=
        Message(
          seq = entry.seq,
          from = Message.FROM_AGENT,
          kind = Message.STEP,
          text = output.trim().take(MAX_STEP_TEXT),
          at = clock(),
          tool = tool.trim().take(MAX_TOOL),
          arguments = arguments.trim().take(MAX_STEP_TEXT),
          ok = ok,
        )
      val steps = entry.messages.count { it.isStep }
      if (steps > MAX_STEPS) entry.messages.remove(entry.messages.first { it.isStep })
      trim(entry)
    }
    entry.bump()
    return true
  }

  /**
   * The person's comments and decisions after [since], waiting up to [waitMs] for one when there
   * are none yet. Null when the session is gone.
   */
  suspend fun awaitFeedback(agent: String, since: Long, waitMs: Long = maxWaitMs): AgentView? {
    var entry = byAgent.value[agent] ?: return null
    seen(agent, "Waiting for feedback") ?: return null
    suspend fun pending() =
      lock.withLock { entry.messages.any { it.from == Message.FROM_PERSON && it.seq > since } }
    val deadline = clock() + waitMs.coerceIn(0, maxWaitMs)
    while (true) {
      // The version before the check: a comment between the two still ends the wait.
      val seenVersion = entry.version.value
      if (pending()) break
      val left = deadline - clock()
      if (left <= 0) break
      withTimeoutOrNull(left) { entry.version.first { it > seenVersion } } ?: break
      entry = byAgent.value[agent] ?: return null
      if (!alive(entry)) return forget(entry)
    }
    return agentView(agent, since)
  }

  suspend fun agentView(agent: String, since: Long = 0): AgentView? {
    val entry = byAgent.value[agent] ?: return null
    if (!alive(entry)) return forget(entry)
    val left = entry.store.touch(entry.owner) ?: return forget(entry)
    return lock.withLock {
      val now = clock()
      AgentView(
        kind = entry.kind,
        title = entry.title,
        messages = entry.messages.filter { it.seq > since && !it.isStep }.takeLast(OWNER_MESSAGES),
        cursor = entry.seq,
        decision = entry.decision,
        personWatching = entry.personSeen > 0 && now - entry.personSeen < WATCHING_MS,
        expiresInSeconds = left,
      )
    }
  }

  // ---- Saved across restarts ----

  /** The relay's part of a session, saved beside the stores' across a restart. */
  @Serializable
  data class Saved(
    val kind: String,
    val owner: String,
    val agent: String,
    val title: String,
    val joinCode: String? = null,
    val joinCodeExpires: Long = 0,
    val agentName: String = "Agent",
    val agentStatus: String = "",
    val agentSeen: Long = 0,
    val seq: Long = 0,
    val messages: List<Message> = emptyList(),
    val decision: String? = null,
    val link: String? = null,
  )

  /**
   * An agent's `request_access`, saved until it expires: still waiting for the person, or granted
   * (into the session [owner] names, or a link as [link]) and not yet collected by `poll_access`.
   */
  @Serializable
  data class SavedRequest(
    val code: String,
    val poll: String,
    val name: String,
    val expires: Long,
    val owner: String? = null,
    val link: String? = null,
  )

  /** Everything to save: sessions, links and access requests. Save again whenever [changes] moves. */
  @Serializable
  data class Snapshot(
    val sessions: List<Saved> = emptyList(),
    val links: List<SessionLinks.Saved> = emptyList(),
    val requests: List<SavedRequest> = emptyList(),
  )

  suspend fun snapshot(): Snapshot {
    val sessions =
      lock.withLock {
        byOwner.value.values.map { e ->
          Saved(
            e.kind,
            e.owner,
            e.agent,
            e.title,
            e.joinCode,
            e.joinCodeExpires,
            e.agentName,
            e.agentStatus,
            e.agentSeen,
            e.seq,
            e.messages.toList(),
            e.decision,
            e.link,
          )
        }
      }
    val pending =
      lock.withLock {
        expireRequests()
        pollers.values.map { SavedRequest(it.code, it.poll, it.name, it.expires, it.entry?.owner, it.link) }
      }
    return Snapshot(sessions, links.snapshot(), pending)
  }

  /** Restore after the stores have restored theirs: only sessions they still hold come back. */
  suspend fun restore(saved: Snapshot) {
    links.restore(saved.links)
    for (s in saved.sessions) {
      if (s.kind !in stores) continue
      val entry = Entry(s.kind, s.owner, s.agent, s.title)
      if (!alive(entry)) continue
      lock.withLock {
        entry.agentName = s.agentName
        entry.agentStatus = s.agentStatus
        entry.agentSeen = s.agentSeen
        entry.seq = s.seq
        entry.messages += s.messages
        entry.decision = s.decision
        entry.link = s.link
        s.joinCode
          ?.takeIf { s.joinCodeExpires > clock() }
          ?.let { code ->
            Codes.normalize(code)?.let { codes[it] = entry }
            entry.joinCode = code
            entry.joinCodeExpires = s.joinCodeExpires
          }
      }
      index(entry)
    }
    lock.withLock {
      for (r in saved.requests) {
        if (r.expires <= clock()) continue
        val request = Request(r.code, r.poll, r.name, r.expires)
        val claimed = r.owner?.let { byOwner.value[it] ?: continue }
        request.entry = claimed
        request.link = r.link
        if (claimed != null || r.link != null) request.done.value = true
        else if (r.code !in codes) requests[r.code] = request
        pollers[r.poll] = request
      }
    }
  }

  // ---- Internals ----

  /** Sessions the relay still indexes. */
  val size: Int
    get() = byOwner.value.size

  /** Access requests still indexed. */
  suspend fun pendingRequests(): Int = lock.withLock { pollers.size + requests.size }

  private fun index(entry: Entry) {
    byOwner.update { it + (entry.owner to entry) }
    byAgent.update { it + (entry.agent to entry) }
  }

  private suspend fun append(entry: Entry, from: String, kind: String, text: String, target: String?) {
    lock.withLock {
      entry.seq++
      entry.messages += Message(entry.seq, from, kind, text, target, clock())
      trim(entry)
    }
    entry.bump()
  }

  /** Under [lock]: the oldest lines go first once there are [MAX_MESSAGES]. */
  private fun trim(entry: Entry) {
    while (entry.messages.size > MAX_MESSAGES) entry.messages.removeAt(0)
  }

  /** Under [lock]. [linkListening] is the link's, when the session went to a link nobody has used yet. */
  private fun presence(entry: Entry, now: Long, linkListening: Boolean?): Presence {
    if (entry.link != null && entry.agentSeen == 0L) {
      val listening = linkListening == true
      return Presence(
        joined = false,
        connected = listening,
        name = entry.agentName,
        status = if (listening) "Picking it up" else "Waiting for it to listen",
        lastSeenMs = null,
      )
    }
    return Presence(
      joined = entry.agentSeen > 0,
      connected = entry.agentSeen > 0 && now - entry.agentSeen < CONNECTED_MS,
      name = entry.agentName,
      status = entry.agentStatus,
      lastSeenMs = entry.agentSeen.takeIf { it > 0 }?.let { now - it },
    )
  }

  private fun alive(entry: Entry): Boolean = entry.store.revision(entry.owner) != null

  /**
   * Drop sessions the stores have expired that nobody came back to. Stores cap live sessions, so
   * sweeping on every registration keeps the relay's indexes (and conversations) bounded too.
   */
  suspend fun prune() {
    byOwner.value.values.filterNot(::alive).forEach { forget<Unit>(it) }
    lock.withLock { expireRequests() }
  }

  private suspend fun <T> forget(entry: Entry): T? {
    byOwner.update { if (it[entry.owner] === entry) it - entry.owner else it }
    byAgent.update { if (it[entry.agent] === entry) it - entry.agent else it }
    lock.withLock { dropCode(entry) }
    entry.bump()
    return null
  }

  /** Under [lock]. */
  private fun issueCode(entry: Entry): String {
    dropCode(entry)
    var code: String
    do code = Codes.random(entropy) while (code in codes || code in requests)
    codes[code] = entry
    val shown = Codes.format(code)
    entry.joinCode = shown
    entry.joinCodeExpires = clock() + CODE_MS
    return shown
  }

  /** Under [lock]. */
  private fun dropCode(entry: Entry) {
    entry.joinCode?.let { Codes.normalize(it)?.let(codes::remove) }
    entry.joinCode = null
    entry.joinCodeExpires = 0
  }

  /**
   * Under [lock]: drop access requests past their lifetime. Sweep [pollers], not only [requests]: a
   * claimed request leaves [requests] at once but waits in [pollers] for its agent's next poll,
   * which may never come.
   */
  private fun expireRequests() {
    val now = clock()
    pollers.values
      .filter { it.expires <= now }
      .forEach {
        requests.remove(it.code)
        pollers.remove(it.poll)
      }
    requests.values.filter { it.expires <= now }.forEach { requests.remove(it.code) }
  }

  /** Under [lock]: one guess budget shared by everyone, since codes are short. */
  private fun throttled(): Boolean {
    val now = clock()
    while (failures.isNotEmpty() && failures.first() <= now - 60_000) failures.removeFirst()
    return failures.size >= MAX_FAILURES_PER_MINUTE
  }

  companion object {
    const val MAX_WAIT_MS: Long = 25_000
    const val CODE_MS: Long = 15 * 60_000L
    const val REQUEST_MS: Long = 10 * 60_000L
    const val CONNECTED_MS: Long = 90_000L
    const val WATCHING_MS: Long = 60_000L
    const val MAX_TEXT: Int = 2000
    const val MAX_TARGET: Int = 200
    const val MAX_TITLE: Int = 200
    const val MAX_STATUS: Int = 120
    const val MAX_TOOL: Int = 64
    const val MAX_STEP_TEXT: Int = 500
    const val MAX_STEPS: Int = 100
    const val MAX_RENDER_BYTES: Int = 1_500_000
    const val MAX_MESSAGES: Int = 300
    const val OWNER_MESSAGES: Int = 150
    const val MAX_REQUESTS: Int = 256
    const val MAX_FAILURES_PER_MINUTE: Int = 30

    const val ACCEPTED: String = "accepted"
    const val REJECTED: String = "rejected"

    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun isPng(bytes: ByteArray): Boolean =
      bytes.size >= PNG.size && PNG.indices.all { bytes[it] == PNG[it] }
  }
}
