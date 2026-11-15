package ee.schimke.livery.publisher

import ee.schimke.composeai.rcplayer.edit.withoutDefaultAnimations
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * A step a template's document goes through as [CatalogBuilder] builds the catalog, before it is checked, hashed and
 * published: what clients download is what the processors made of the `.rc` in the tree. A processor returns the bytes
 * it was given when it has nothing to change, so a document it leaves alone keeps its hash.
 */
fun interface TemplateProcessor {
  fun process(bytes: ByteString, spec: TemplateSpec, config: CatalogConfig): ByteString

  companion object {
    /**
     * rc-player-edit's `withoutDefaultAnimations`: every component without an animation spec gets a disabled one, so
     * only what the author chose to animate animates, in every player. Skipped for a template whose sidecar, or a
     * catalog whose `catalog.json`, sets `defaultAnimations: true`, and for a document it cannot decode.
     */
    val WithoutDefaultAnimations: TemplateProcessor = TemplateProcessor { bytes, spec, config ->
      if (spec.defaultAnimations ?: config.defaultAnimations) return@TemplateProcessor bytes
      val document = runCatching { RcDocumentCodec.decode(bytes.toByteArray()) }.getOrNull() ?: return@TemplateProcessor bytes
      val rewritten = document.withoutDefaultAnimations()
      if (rewritten === document) bytes else RcDocumentCodec.encode(rewritten).toByteString()
    }

    /** What [CatalogBuilder] runs unless it is given other processors. */
    val Defaults: List<TemplateProcessor> = listOf(WithoutDefaultAnimations)
  }
}
