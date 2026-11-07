package ee.schimke.livery.instantiate

import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcBitmapFontData
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcFontData
import ee.schimke.composeai.rcplayer.protocol.RcHapticFeedback
import ee.schimke.composeai.rcplayer.protocol.RcParticleCompare
import ee.schimke.composeai.rcplayer.protocol.RcParticleDefine
import ee.schimke.composeai.rcplayer.protocol.RcParticleLoop
import ee.schimke.composeai.rcplayer.protocol.RcPlaySound
import ee.schimke.composeai.rcplayer.protocol.RcShaderData
import ee.schimke.composeai.rcplayer.protocol.RcSoundData
import ee.schimke.composeai.rcplayer.protocol.RcSoundExpression
import ee.schimke.composeai.rcplayer.protocol.RcWakeIn
import ee.schimke.livery.client.ContractEvolution
import ee.schimke.livery.client.Crypto
import ee.schimke.livery.client.TemplateSignatures
import ee.schimke.livery.client.TemplateVerification
import ee.schimke.livery.client.TrustedKey
import ee.schimke.livery.protocol.ActionKinds
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.ManifestSignature

/**
 * What a document can do beyond drawing its contract's data and sending its contract's actions
 * (PROTOCOL.md §3.5): GPU code, the device's motor and speaker, unbounded animation, pixels and
 * fonts that bypass the client's image and font rules, and waking itself on a timer. A document
 * that arrives with a message is held to them: a signed one may use only those the app opted into,
 * an unsigned one none at all.
 */
enum class RiskyFeature(val wire: String) {
  /** Runtime shaders: GPU programs, and the most convincing way to imitate anything. */
  Shaders("shaders"),
  Haptics("haptics"),
  Sound("sound"),
  /** Particle systems: unbounded per-frame work. */
  Particles("particles"),
  /** Image bytes in the document itself, never seen by the client's image rules (PROTOCOL.md §6). */
  EmbeddedImages("embedded-images"),
  /** Font bytes in the document itself, not the catalog's fonts. */
  EmbeddedFonts("embedded-fonts"),
  /** Scheduling its own redraws. */
  Wake("wake"),
  ;

  companion object {
    /** The features [document]'s operations use. Its operations are a flat list (containers are start/end markers). */
    fun of(document: RcDocument): Set<RiskyFeature> = document.operations.mapNotNullTo(LinkedHashSet()) { op ->
      when (op) {
        is RcShaderData -> Shaders
        is RcHapticFeedback -> Haptics
        is RcPlaySound, is RcSoundData, is RcSoundExpression -> Sound
        is RcParticleDefine, is RcParticleLoop, is RcParticleCompare -> Particles
        is RcBitmapData -> EmbeddedImages.takeIf { op.encoding == RcBitmapData.ENCODING_INLINE }
        is RcFontData, is RcBitmapFontData -> EmbeddedFonts
        is RcWakeIn -> Wake
        else -> null
      }
    }

    fun fromWire(name: String): RiskyFeature? = entries.firstOrNull { it.wire == name }
  }
}

/**
 * The app's opt-in to unsigned documents from an agent (docs/A2UI.md): off unless the app sets one.
 * Only for [contractPrefixes], at most [maxBytes], never with a [RiskyFeature], and never emitting
 * `open-url` or `present` unless [allowedKinds] lists it.
 */
data class AgentBytesPolicy(
  val contractPrefixes: Set<String>,
  val maxBytes: Int = 64 * 1024,
  val allowedKinds: Set<String> = emptySet(),
) {
  fun allows(contractRef: String): Boolean = contractPrefixes.any { contractRef.startsWith(it) }

  companion object {
    /** Kinds an unsigned document may emit only when [allowedKinds] names them: they leave the slot. */
    val GUARDED_KINDS: Set<String> = setOf(ActionKinds.OPEN_URL, ActionKinds.PRESENT)
  }
}

sealed interface Admission {
  /** May play, exactly these [bytes]; [signedBy] is the key id, or null when unsigned. */
  class Admitted(val bytes: ByteArray, val sha256: String, val signedBy: String?, val features: Set<RiskyFeature>) : Admission
  data class Refused(val reason: String) : Admission
}

/**
 * The one gate for a document that arrives with a message rather than through a catalog
 * (PROTOCOL.md §3.4, §3.5). In order: its size; its hash; a template signature by one of [keys]
 * that may sign its contract, or else the app's [agentBytes] policy for unsigned documents; that it
 * decodes, reads only what its contract can produce (as the contract types it) and emits only the
 * contract's actions; and its [RiskyFeature]s: a signed document may use only [signedFeatures], an
 * unsigned one none. Nothing here fetches anything or keeps the bytes.
 */
class DocumentAdmission(
  private val crypto: Crypto,
  private val keys: List<TrustedKey>,
  /** The risky features a signed document may use: none unless the app opts in. */
  val signedFeatures: Set<RiskyFeature> = emptySet(),
  /** Unsigned documents: refused unless the app sets a policy. */
  val agentBytes: AgentBytesPolicy? = null,
  private val maxSignedBytes: Int = 256 * 1024,
) {
  suspend fun admit(bytes: ByteArray, contract: Contract, signature: ManifestSignature?): Admission {
    val ref = contract.ref
    val limit = if (signature != null) maxSignedBytes else agentBytes?.maxBytes ?: 0
    if (signature == null && agentBytes == null) return Admission.Refused("unsigned documents are not allowed")
    if (signature == null && agentBytes?.allows(ref) != true) return Admission.Refused("unsigned documents may not play as $ref")
    if (bytes.size > limit) return Admission.Refused("${bytes.size} bytes is over the ${if (signature != null) "signed" else "unsigned"} limit of $limit")
    val sha = crypto.sha256Hex(bytes)
    val signedBy = signature?.let {
      when (val v = TemplateSignatures.verify(crypto, keys, ref, sha, it)) {
        is TemplateVerification.Accepted -> v.key.keyId
        is TemplateVerification.Rejected -> return Admission.Refused("signature: ${v.reason}")
      }
    }
    val facts = TemplateInspector.facts(bytes) ?: return Admission.Refused("not a Remote Compose document")
    val unknownReads = facts.declaredReads.filter { name -> ContractEvolution.specOf(contract, name) == null && contract.lists.keys.none { name == "$it.count" } }
    if (unknownReads.isNotEmpty()) return Admission.Refused("reads ${unknownReads.sorted()}, which $ref doesn't declare")
    TemplateInspector.typeProblems(bytes, contract).firstOrNull()?.let { return Admission.Refused(it) }
    val unknownEmits = facts.emits - contract.actions.keys
    if (unknownEmits.isNotEmpty()) return Admission.Refused("emits ${unknownEmits.sorted()}, which $ref doesn't declare")
    if (signedBy == null) {
      if (facts.features.isNotEmpty()) return Admission.Refused("an unsigned document may not use ${facts.features.map { it.wire }}")
      val policy = agentBytes!!
      val guarded = facts.emits.filter { contract.actions[it]?.kind in AgentBytesPolicy.GUARDED_KINDS && contract.actions[it]?.kind !in policy.allowedKinds }
      if (guarded.isNotEmpty()) return Admission.Refused("an unsigned document may not emit ${guarded.sorted()} (${guarded.map { contract.actions.getValue(it).kind }.distinct().sorted()})")
    } else {
      val notAllowed = facts.features - signedFeatures
      if (notAllowed.isNotEmpty()) return Admission.Refused("uses ${notAllowed.map { it.wire }}, which the app hasn't allowed for signed documents")
    }
    return Admission.Admitted(bytes, sha, signedBy, facts.features)
  }
}
