package ee.schimke.livery.sessions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/**
 * A person's lasting connections to their agents (SESSIONS.md, Model): set up once (the agent
 * redeems a code, or the person enters the agent's), then every session the person starts is
 * delivered to that agent, which listens with `await_request`. Nothing is copied after the setup.
 *
 * A link holds no credential and grants nothing by itself: each delivered request carries its own
 * session capability. A link ends [IDLE_MS] after either side last used it, or when revoked.
 */
class SessionLinks(
  private val entropy: Entropy,
  private val clock: () -> Long,
  private val maxWaitMs: Long = SessionRelay.MAX_WAIT_MS,
  private val changed: () -> Unit = {},
) {
  /** A request delivered to the linked agent: the session to work in. */
  @Serializable
  data class Delivery(val seq: Long, val session: String, val kind: String, val title: String)

  data class Created(val owner: String, val code: String)

  /** What the person's settings show. */
  data class State(
    val version: Long,
    val connected: Boolean,
    val name: String,
    val listening: Boolean,
    val lastSeenMs: Long?,
    val code: String?,
    val codeExpiresInSeconds: Long,
  )

  data class Inbox(val requests: List<Delivery>, val cursor: Long)

  private inner class Link(val owner: String, var agent: String) {
    val version = MutableStateFlow(1L)
    var connected = false
    var name = "Agent"
    var agentSeen = 0L
    var activeAt = clock()
    var code: String? = null
    var codeExpires = 0L
    var seq = 0L
    val inbox = ArrayList<Delivery>()

    fun bump() {
      activeAt = clock()
      version.update { it + 1 }
      changed()
    }
  }

  private val lock = Mutex()
  private val byOwner = HashMap<String, Link>()
  private val byAgent = HashMap<String, Link>()
  private val codes = HashMap<String, Link>()

  /** Start the setup: a link waiting for its agent, and the code to give it. */
  suspend fun create(): Created =
    lock.withLock {
      prune()
      check(byOwner.size < MAX_LINKS) { "Too many agent links" }
      val link = Link(entropy.token(), entropy.token())
      byOwner[link.owner] = link
      byAgent[link.agent] = link
      val code = issueCode(link)
      link.bump()
      Created(link.owner, code)
    }

  suspend fun isOwner(owner: String): Boolean =
    owner.isNotEmpty() && lock.withLock { live(byOwner[owner]) != null }

  /** Whether [code] (normalized) is an unused link code: link and session codes share one form. */
  suspend fun hasCode(code: String): Boolean = lock.withLock { code in codes }

  /** Redeem a link code ([Codes.normalize] form): the agent's link capability, or null. */
  suspend fun connect(code: String, agentName: String?): String? =
    lock.withLock {
      val link = codes.remove(code)?.takeIf { it.codeExpires > clock() } ?: return null
      attach(link, agentName)
    }

  /** The person entered the code an agent showed them (`request_access`): that agent's link. */
  suspend fun attachAgent(owner: String, agentName: String?): String? =
    lock.withLock { live(byOwner[owner])?.let { attach(it, agentName) } }

  /** The person's view of their link, now or once it changes past [since]. */
  suspend fun state(owner: String, since: Long? = null): State? {
    val link = lock.withLock { live(byOwner[owner]) } ?: return null
    if (since != null && link.version.value <= since)
      withTimeoutOrNull(maxWaitMs) { link.version.first { it > since } }
    return lock.withLock {
      if (live(byOwner[owner]) == null) return null
      val now = clock()
      link.activeAt = now
      if (link.codeExpires <= now) dropCode(link)
      State(
        version = link.version.value,
        connected = link.connected,
        name = link.name,
        listening = link.agentSeen > 0 && now - link.agentSeen < SessionRelay.CONNECTED_MS,
        lastSeenMs = link.agentSeen.takeIf { it > 0 }?.let { now - it },
        code = link.code,
        codeExpiresInSeconds = ((link.codeExpires - now) / 1000).coerceAtLeast(0),
      )
    }
  }

  /** A fresh code, to connect another agent in place of this one. */
  suspend fun newCode(owner: String): String? =
    lock.withLock {
      val link = live(byOwner[owner]) ?: return null
      issueCode(link).also { link.bump() }
    }

  /** The linked agent's name when [owner] names a connected link: where a new request went. */
  suspend fun deliver(owner: String, session: String, kind: String, title: String): String? =
    lock.withLock {
      val link = live(byOwner[owner])?.takeIf { it.connected } ?: return null
      link.seq++
      link.inbox += Delivery(link.seq, session, kind, title)
      if (link.inbox.size > MAX_INBOX) link.inbox.removeAt(0)
      link.bump()
      link.name
    }

  suspend fun listening(owner: String): Boolean =
    lock.withLock {
      val link = live(byOwner[owner]) ?: return false
      link.agentSeen > 0 && clock() - link.agentSeen < SessionRelay.CONNECTED_MS
    }

  /**
   * The agent's side: requests delivered after [since], waiting up to [maxWaitMs] for one. Each
   * call counts as listening, which the person sees. Null once the link is gone.
   */
  suspend fun awaitRequests(agent: String, since: Long): Inbox? {
    val link =
      lock.withLock {
        val link = live(byAgent[agent]) ?: return null
        link.agentSeen = clock()
        link.bump()
        link
      }
    val deadline = clock() + maxWaitMs
    while (true) {
      // The version before the check: a delivery between the two still ends the wait.
      val version = link.version.value
      if (lock.withLock { link.inbox.any { it.seq > since } }) break
      val left = deadline - clock()
      if (left <= 0) break
      withTimeoutOrNull(left) { link.version.first { it > version } } ?: break
      if (lock.withLock { live(byAgent[agent]) } !== link) return null
    }
    return lock.withLock {
      // Replaced by another agent while it waited: what it was waiting for is no longer its own.
      if (live(byAgent[agent]) !== link) return null
      link.agentSeen = clock()
      link.activeAt = link.agentSeen
      Inbox(link.inbox.filter { it.seq > since }, link.seq)
    }
  }

  suspend fun revoke(owner: String): Boolean = lock.withLock { remove(owner) }

  // ---- Saved across restarts ----

  @Serializable
  data class Saved(
    val owner: String,
    val agent: String,
    val connected: Boolean,
    val name: String,
    val agentSeen: Long,
    val activeAt: Long,
    val code: String? = null,
    val codeExpires: Long = 0,
    val seq: Long = 0,
    val inbox: List<Delivery> = emptyList(),
  )

  suspend fun snapshot(): List<Saved> =
    lock.withLock {
      byOwner.values.map {
        Saved(
          it.owner,
          it.agent,
          it.connected,
          it.name,
          it.agentSeen,
          it.activeAt,
          it.code,
          it.codeExpires,
          it.seq,
          it.inbox.toList(),
        )
      }
    }

  suspend fun restore(saved: List<Saved>) =
    lock.withLock {
      for (s in saved) {
        if (s.activeAt + IDLE_MS <= clock()) continue
        val link = Link(s.owner, s.agent)
        link.connected = s.connected
        link.name = s.name
        link.agentSeen = s.agentSeen
        link.activeAt = s.activeAt
        link.seq = s.seq
        link.inbox += s.inbox
        s.code
          ?.takeIf { s.codeExpires > clock() }
          ?.let { code ->
            Codes.normalize(code)?.let { codes[it] = link }
            link.code = code
            link.codeExpires = s.codeExpires
          }
        byOwner[s.owner] = link
        byAgent[s.agent] = link
      }
    }

  // ---- Internals: every one runs under [lock] ----

  /**
   * Connect [link]'s agent. Replacing one already connected rotates the agent capability, so the
   * previous agent stops receiving the person's requests at once, a waiting `await_request`
   * included.
   */
  private fun attach(link: Link, agentName: String?): String {
    dropCode(link)
    if (link.connected) {
      byAgent.remove(link.agent)
      link.agent = entropy.token()
      byAgent[link.agent] = link
    }
    link.connected = true
    link.name = Codes.cleanName(agentName)
    link.agentSeen = clock()
    link.bump()
    return link.agent
  }

  private fun remove(owner: String): Boolean {
    val link = byOwner.remove(owner) ?: return false
    byAgent.remove(link.agent)
    dropCode(link)
    link.bump()
    return true
  }

  private fun live(link: Link?): Link? {
    link ?: return null
    if (link.activeAt + IDLE_MS > clock()) return link
    remove(link.owner)
    return null
  }

  private fun prune() {
    byOwner.values.toList().forEach { live(it) }
  }

  private fun issueCode(link: Link): String {
    dropCode(link)
    var code: String
    do code = Codes.random(entropy) while (code in codes)
    codes[code] = link
    link.code = Codes.format(code)
    link.codeExpires = clock() + SessionRelay.CODE_MS
    return link.code!!
  }

  private fun dropCode(link: Link) {
    link.code?.let { Codes.normalize(it)?.let(codes::remove) }
    link.code = null
    link.codeExpires = 0
  }

  companion object {
    const val IDLE_MS: Long = 30L * 24 * 60 * 60_000
    const val MAX_LINKS: Int = 4096
    const val MAX_INBOX: Int = 50
  }
}
