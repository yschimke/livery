package ee.schimke.livery.instantiate

import ee.schimke.composeai.rcplayer.edit.namedVariables
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.composeai.rcwidget.RcHostActionRewrites
import ee.schimke.composeai.rcwidget.RcWearContainer

/** What a document really reads and emits, read from its operations rather than its sidecar. */
data class TemplateFacts(val bytes: Int, val reads: Set<String>, val emits: Set<String>)

/**
 * The deep check of a template (DESIGN.md: the publisher's `inspect` hook): it decodes, and it reads
 * and emits only what its sidecar declares. A sidecar is written by whatever produced the document,
 * an agent among them, so `livery build --inspect` and every agent draft compare it with the
 * document itself.
 */
object TemplateInspector {
  /** The document's facts, or null when the bytes aren't a Remote Compose document. */
  fun facts(template: ByteArray): TemplateFacts? = runCatching {
    val d = RcDocumentCodec.decode(template, RcWireLimits())
    TemplateFacts(template.size, d.namedVariables.map { it.name.removePrefix("USER:") }.toSet(), RcHostActionRewrites.actionNames(d))
  }.getOrNull()

  /**
   * Problems with [template] against its sidecar's [reads] and [emits]; empty when it is consistent.
   * Names a host writes itself (a Wear container's `wear.container.*`) need no declaration.
   */
  fun problems(template: ByteArray, reads: Collection<String>, emits: Collection<String>): List<String> {
    val f = facts(template) ?: return listOf("not a Remote Compose document")
    val hostNames = RcWearContainer.FRAME_NAMES + RcWearContainer.COLOR
    return buildList {
      val undeclared = f.reads - reads.toSet() - hostNames
      if (undeclared.isNotEmpty()) add("reads ${undeclared.sorted()} that its sidecar doesn't declare")
      val unlisted = f.emits - emits.toSet()
      if (unlisted.isNotEmpty()) add("emits ${unlisted.sorted()} that its sidecar doesn't declare")
    }
  }
}
