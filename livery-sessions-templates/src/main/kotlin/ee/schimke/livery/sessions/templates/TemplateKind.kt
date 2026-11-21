package ee.schimke.livery.sessions.templates

import ee.schimke.livery.client.ContractEvolution
import ee.schimke.livery.instantiate.RiskyFeature
import ee.schimke.livery.instantiate.TemplateFacts
import ee.schimke.livery.instantiate.TemplateInspector
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.Target
import ee.schimke.livery.sessions.DraftStore
import ee.schimke.livery.sessions.Entropy
import ee.schimke.livery.sessions.SecureEntropy
import ee.schimke.livery.sessions.server.DraftResult
import ee.schimke.livery.sessions.server.SessionKind
import ee.schimke.livery.sessions.server.SessionServer
import ee.schimke.livery.sessions.server.SessionTool
import ee.schimke.livery.sessions.server.SessionToolResult
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Livery's own kind of session (SESSIONS.md): an agent drafts a Remote Compose template for one
 * [Contract] and [Target], and the person plays each draft, with the contract's examples or their
 * own data, before deciding.
 *
 * A draft is the document's bytes (`documentBase64`). Its gate is the one a document arriving
 * outside a catalog passes (PROTOCOL.md §3.5, `DocumentAdmission`), as unsigned bytes: it decodes,
 * reads only what the contract can produce at the contract's types, emits only the contract's
 * actions, uses none of the [RiskyFeature]s the app hasn't [allowed], and fits in [maxBytes]. An
 * accepted draft is still only a draft: it plays in this session's preview, and reaches a catalog
 * only through the app's own publishing, signed by a key the agent doesn't hold.
 */
class TemplateKind(
  private val clock: () -> Long = System::currentTimeMillis,
  private val entropy: Entropy = SecureEntropy(),
  val maxBytes: Int = 256 * 1024,
  /** The risky features a draft may use; none by default, as for any unsigned document. */
  val allowed: Set<RiskyFeature> = emptySet(),
  private val idleMs: Long = 60 * 60_000L,
  private val maxLifeMs: Long = 24 * 60 * 60_000L,
  private val maxSessions: Int = 512,
) : SessionKind {
  override val id: String = ID

  /** A template session as the person's app reads it: what to play now. */
  data class Draft(
    val contract: Contract,
    val target: Target,
    val title: String,
    /** The draft, or the starting document until the agent submits one; null when neither. */
    val bytes: ByteString?,
    val sha256: String?,
    val revision: Long,
    val facts: TemplateFacts?,
  )

  data class Created(val owner: String, val agent: String)

  @Serializable
  private data class Saved(
    val owner: String,
    val agent: String,
    val contract: Contract,
    val target: Target,
    val title: String,
    val document: String? = null,
    val revision: Long = 0,
    val createdAt: Long,
    val activeAt: Long,
  )

  private class Entry(
    val owner: String,
    val agent: String,
    val contract: Contract,
    val target: Target,
    val title: String,
    var document: ByteString?,
    var revision: Long,
    val createdAt: Long,
    var activeAt: Long,
  )

  private val lock = Any()
  private val byOwner = HashMap<String, Entry>()
  private val byAgent = HashMap<String, Entry>()
  private var server: SessionServer? = null

  override fun attached(server: SessionServer) {
    this.server = server
  }

  /**
   * A new session on [contract] for [target], starting from [document] (an existing design's
   * template) or from nothing. Start it with [SessionServer.start] to get the code for the agent.
   */
  fun create(contract: Contract, target: Target, title: String, document: ByteString? = null): Created =
    synchronized(lock) {
      prune()
      check(byOwner.size < maxSessions) { "Too many template sessions" }
      document?.let { require(it.size <= maxBytes) { "The starting document is over $maxBytes bytes" } }
      val now = clock()
      val entry = Entry(entropy.token(), entropy.token(), contract, target, title.take(200), document, 0, now, now)
      byOwner[entry.owner] = entry
      byAgent[entry.agent] = entry
      Created(entry.owner, entry.agent)
    }

  /** The person's view: what to play now, or null once the session is gone. */
  fun draft(owner: String): Draft? =
    synchronized(lock) {
      val e = live(byOwner[owner]) ?: return null
      val bytes = e.document
      Draft(e.contract, e.target, e.title, bytes, bytes?.let(::sha256), e.revision, bytes?.let(TemplateInspector::facts))
    }

  override val store: DraftStore =
    object : DraftStore {
      override fun revision(owner: String) = synchronized(lock) { live(byOwner[owner])?.revision }

      override fun touch(owner: String): Long? =
        synchronized(lock) {
          val e = live(byOwner[owner]) ?: return null
          e.activeAt = clock()
          (left(e) / 1000).coerceAtLeast(0)
        }

      override fun revoke(owner: String) = synchronized(lock) { remove(byOwner[owner]) }
    }

  override fun read(agent: String): JsonObject? =
    synchronized(lock) {
      val e = live(byAgent[agent]) ?: return null
      e.activeAt = clock()
      buildJsonObject {
        put("contract", JSON.encodeToJsonElement(Contract.serializer(), e.contract))
        put("target", JSON.encodeToJsonElement(Target.serializer(), e.target))
        put("revision", e.revision)
        put("maxBytes", maxBytes)
        putJsonArray("allowedFeatures") { allowed.forEach { add(it.wire) } }
        e.document?.let { doc ->
          put("documentBase64", Base64.getEncoder().encodeToString(doc.toByteArray()))
          put("sha256", sha256(doc))
          TemplateInspector.facts(doc)?.let { put("facts", it.json()) }
        }
      }
    }

  override fun submit(agent: String, revision: Long, draft: JsonObject): DraftResult {
    val (contract, current) = synchronized(lock) { live(byAgent[agent])?.let { it.contract to it.revision } } ?: return DraftResult.Gone
    if (revision != current) return DraftResult.Refused("The draft is at revision $current; read it with get_session and submit against that.")
    val bytes =
      when (val checked = check(contract, draft)) {
        is Checked.Refused -> return DraftResult.Refused(checked.reason)
        is Checked.Passed -> checked.bytes
      }
    val owner =
      synchronized(lock) {
        val e = live(byAgent[agent]) ?: return DraftResult.Gone
        if (e.revision != revision) return DraftResult.Refused("The draft moved to revision ${e.revision} meanwhile; read it again.")
        e.document = bytes
        e.revision++
        e.activeAt = clock()
        e.owner
      }
    server?.changed(owner)
    return DraftResult.Accepted(
      buildJsonObject {
        put("revision", revision + 1)
        put("sha256", sha256(bytes))
      }
    )
  }

  override val submitWith: String =
    "submit_draft with revision and documentBase64: the complete Remote Compose document, reading only the contract's inputs and emitting only its actions"

  override val tools: List<SessionTool> =
    listOf(
      object : SessionTool {
        override val name = "check_draft"
        override val description =
          "Check a template draft (documentBase64) against the session's contract without submitting it: what it reads, emits and uses, or why it would be refused."
        override val inputSchema = buildJsonObject {
          put("type", "object")
          putJsonObject("properties") { putJsonObject("documentBase64") { put("type", "string") } }
          putJsonArray("required") { add("documentBase64") }
        }

        override suspend fun call(agent: String, arguments: JsonObject): SessionToolResult {
          val contract = synchronized(lock) { live(byAgent[agent])?.contract } ?: return SessionToolResult.Failed("This session ended or expired.")
          return when (val checked = check(contract, arguments)) {
            is Checked.Refused -> SessionToolResult.Failed(checked.reason)
            is Checked.Passed ->
              SessionToolResult.Done(
                buildJsonObject {
                  put("status", "would be accepted")
                  put("facts", checked.facts.json())
                }
              )
          }
        }
      }
    )

  override fun save(): JsonElement =
    synchronized(lock) {
      prune()
      JSON.encodeToJsonElement(
        ListSerializer(Saved.serializer()),
        byOwner.values.map {
          Saved(it.owner, it.agent, it.contract, it.target, it.title, it.document?.base64(), it.revision, it.createdAt, it.activeAt)
        },
      )
    }

  override fun restore(saved: JsonElement) =
    synchronized(lock) {
      for (s in JSON.decodeFromJsonElement(ListSerializer(Saved.serializer()), saved)) {
        val e = Entry(s.owner, s.agent, s.contract, s.target, s.title, s.document?.let { Base64.getDecoder().decode(it).toByteString() }, s.revision, s.createdAt, s.activeAt)
        if (left(e) <= 0) continue
        byOwner[e.owner] = e
        byAgent[e.agent] = e
      }
    }

  // ---- The gate ----

  private sealed interface Checked {
    data class Passed(val bytes: ByteString, val facts: TemplateFacts) : Checked

    data class Refused(val reason: String) : Checked
  }

  /** The draft in [arguments] held to [contract]: the checks of PROTOCOL.md §3.5 for unsigned bytes. */
  private fun check(contract: Contract, arguments: JsonObject): Checked {
    val encoded = (arguments["documentBase64"] as? JsonPrimitive)?.contentOrNull ?: return Checked.Refused("Pass documentBase64: the document's bytes, base64.")
    // Base64 is 4/3 the size: refuse an oversized draft before decoding it.
    if (encoded.length > (maxBytes / 3 + 1) * 4) return Checked.Refused("The document is over $maxBytes bytes.")
    val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()?.toByteString() ?: return Checked.Refused("documentBase64 isn't base64.")
    if (bytes.size > maxBytes) return Checked.Refused("The document is ${bytes.size} bytes, over $maxBytes.")
    val ref = contract.ref
    val facts = TemplateInspector.facts(bytes) ?: return Checked.Refused("That isn't a Remote Compose document.")
    val unknownReads = facts.declaredReads.filter { name -> ContractEvolution.specOf(contract, name) == null && contract.lists.keys.none { name == "$it.count" } }
    if (unknownReads.isNotEmpty()) return Checked.Refused("It reads ${unknownReads.sorted()}, which $ref doesn't declare.")
    TemplateInspector.typeProblems(bytes, contract).firstOrNull()?.let { return Checked.Refused(it) }
    val unknownEmits = facts.emits - contract.actions.keys
    if (unknownEmits.isNotEmpty()) return Checked.Refused("It emits ${unknownEmits.sorted()}, which $ref doesn't declare.")
    val notAllowed = facts.features - allowed
    if (notAllowed.isNotEmpty()) return Checked.Refused("It uses ${notAllowed.map { it.wire }.sorted()}, which drafts here may not.")
    return Checked.Passed(bytes, facts)
  }

  // ---- Internals: under [lock] ----

  private fun left(e: Entry) = minOf(e.activeAt + idleMs, e.createdAt + maxLifeMs) - clock()

  private fun live(e: Entry?): Entry? {
    e ?: return null
    if (left(e) > 0) return e
    remove(e)
    return null
  }

  private fun remove(e: Entry?): Boolean {
    e ?: return false
    byOwner.remove(e.owner)
    byAgent.remove(e.agent)
    return true
  }

  private fun prune() {
    byOwner.values.toList().forEach { live(it) }
  }

  companion object {
    const val ID: String = "template"
    private val JSON = Json {
      ignoreUnknownKeys = true
      encodeDefaults = false
    }

    private fun sha256(bytes: ByteString): String =
      MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun TemplateFacts.json() = buildJsonObject {
      putJsonArray("reads") { declaredReads.sorted().forEach { add(it) } }
      putJsonArray("emits") { emits.sorted().forEach { add(it) } }
      putJsonArray("features") { features.map { it.wire }.sorted().forEach { add(it) } }
      put("bytes", bytes)
    }
  }
}
