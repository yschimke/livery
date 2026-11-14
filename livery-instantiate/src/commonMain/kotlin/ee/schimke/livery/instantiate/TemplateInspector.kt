package ee.schimke.livery.instantiate

import okio.ByteString
import ee.schimke.composeai.rcplayer.edit.bitmapUrls
import ee.schimke.composeai.rcplayer.edit.namedVariables
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.composeai.rcwidget.RcHostActionRewrites
import ee.schimke.composeai.rcwidget.RcWearContainer
import ee.schimke.livery.client.ContractEvolution
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.ScreenValues
import ee.schimke.livery.protocol.ValueType

/** What a document really reads and emits, read from its operations rather than its sidecar. */
data class TemplateFacts(
  val bytes: Int,
  val reads: Set<String>,
  val emits: Set<String>,
  /**
   * Every Remote Compose type (`RcNamedVariable.STRING_TYPE`, …) each value is used as: a named
   * value's comes from its default in the document, a data-bound image's is an image.
   */
  val types: Map<String, Set<Int>> = emptyMap(),
  /** The [RiskyFeature]s its operations use: what a document arriving outside a catalog is held to. */
  val features: Set<RiskyFeature> = emptySet(),
) {
  /** [reads] without the names a host writes itself: what a sidecar declares. */
  val declaredReads: Set<String> get() = reads - TemplateInspector.HOST_NAMES
}

/**
 * The deep check of a template (DESIGN.md: the publisher's `inspect` hook): it decodes, and it reads
 * and emits only what its sidecar declares. A sidecar is written by whatever produced the document,
 * an agent among them, so `livery build --inspect` and every agent draft compare it with the
 * document itself.
 */
object TemplateInspector {
  /** Named values a host writes into a document itself (a Wear container's frame, a screen's insets), never data. */
  val HOST_NAMES: Set<String> = RcWearContainer.FRAME_NAMES + RcWearContainer.COLOR + ScreenValues.NAMES

  /** The document's facts, or null when the bytes aren't a Remote Compose document. */
  fun facts(template: ByteString): TemplateFacts? = runCatching {
    val d = RcDocumentCodec.decode(template.toByteArray(), RcWireLimits())
    // A data-bound image (`livery:value/<input>`) is a read too: the player never sees it as a named
    // value, but the slot's binding for that input is what it draws.
    val images = d.bitmapUrls.filter { it.startsWith(TemplateInstantiator.VALUE_PREFIX) }.map { it.removePrefix(TemplateInstantiator.VALUE_PREFIX) }
    // A data-bound image is drawn from an image binding, so its input must be an image; one input
    // used both ways keeps both types, so a conflict is caught.
    val uses = d.namedVariables.map { it.name.removePrefix("USER:") to it.type } + images.map { it to RcNamedVariable.IMAGE_TYPE }
    val types = uses.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.toSet() }
    TemplateFacts(template.size, types.keys, RcHostActionRewrites.actionNames(d), types, RiskyFeature.of(d))
  }.getOrNull()

  /**
   * Problems with [template] against its sidecar's [reads] and [emits]; empty when it is consistent.
   * Names a host writes itself (a Wear container's `wear.container.*`) need no declaration.
   */
  fun problems(template: ByteString, reads: Collection<String>, emits: Collection<String>): List<String> {
    val f = facts(template) ?: return listOf("not a Remote Compose document")
    return buildList {
      val undeclared = f.declaredReads - reads.toSet()
      if (undeclared.isNotEmpty()) add("reads ${undeclared.sorted()} that its sidecar doesn't declare")
      val unlisted = f.emits - emits.toSet()
      if (unlisted.isNotEmpty()) add("emits ${unlisted.sorted()} that its sidecar doesn't declare")
    }
  }

  /**
   * Values [template] reads as a different type than [contract] declares: a document's named value
   * carries a typed default, and a value of the wrong type would never be written into it.
   */
  fun typeProblems(template: ByteString, contract: Contract): List<String> {
    val f = facts(template) ?: return listOf("not a Remote Compose document")
    return f.types.entries.sortedBy { it.key }.flatMap { (name, used) -> used.sorted().map { name to it } }.mapNotNull { (name, rcType) ->
      // A list's count is always bound as an int (PROTOCOL.md §4.1).
      val type = ContractEvolution.specOf(contract, name)?.type
        ?: ValueType.Int.takeIf { contract.lists.keys.any { name == "$it.count" } }
        ?: return@mapNotNull null
      val ok = RC_TYPES[type] ?: return@mapNotNull null
      if (rcType in ok) null else "reads '$name' as ${rcTypeName(rcType)}, but ${contract.ref} declares ${type.name.lowercase()}"
    }
  }

  /** The Remote Compose named-value types that hold each contract type; a named boolean is an int. */
  private val RC_TYPES: Map<ValueType, Set<Int>> = mapOf(
    ValueType.String to setOf(RcNamedVariable.STRING_TYPE),
    ValueType.Int to setOf(RcNamedVariable.INT_TYPE),
    ValueType.Long to setOf(RcNamedVariable.LONG_TYPE, RcNamedVariable.INT_TYPE),
    ValueType.Float to setOf(RcNamedVariable.FLOAT_TYPE),
    ValueType.Bool to setOf(RcNamedVariable.INT_TYPE),
    ValueType.Color to setOf(RcNamedVariable.COLOR_TYPE),
    ValueType.Image to setOf(RcNamedVariable.IMAGE_TYPE),
  )

  private fun rcTypeName(t: Int): String = when (t) {
    RcNamedVariable.STRING_TYPE -> "a string"
    RcNamedVariable.INT_TYPE -> "an int"
    RcNamedVariable.LONG_TYPE -> "a long"
    RcNamedVariable.FLOAT_TYPE -> "a float"
    RcNamedVariable.COLOR_TYPE -> "a color"
    RcNamedVariable.IMAGE_TYPE -> "an image"
    RcNamedVariable.FLOAT_ARRAY_TYPE -> "a float array"
    else -> "type $t"
  }
}
