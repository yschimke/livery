package ee.schimke.livery.capture

import okio.ByteString
import ee.schimke.livery.instantiate.TemplateInspector
import ee.schimke.livery.protocol.Target
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One captured template: its bytes and the sidecar Livery's catalog reads beside it. */
class CapturedTemplate(
  val bytes: ByteString,
  val target: Target,
  val reads: List<String>,
  val emits: List<String>,
  val source: Map<String, String>,
  /** The font families the document names (the sidecar's `fonts`), so their files download with it. */
  val fonts: List<String> = emptyList(),
) {
  /** The `<name>.json` sidecar, as `livery build` reads it. */
  fun sidecar(): String = JSON.encodeToString(JsonObject.serializer(), buildJsonObject {
    put("target", JSON.encodeToJsonElement(Target.serializer(), target))
    put("reads", buildJsonArray { reads.forEach { add(JsonPrimitive(it)) } })
    put("emits", buildJsonArray { emits.forEach { add(JsonPrimitive(it)) } })
    if (fonts.isNotEmpty()) put("fonts", buildJsonArray { fonts.forEach { add(JsonPrimitive(it)) } })
    if (source.isNotEmpty()) put("source", buildJsonObject { source.forEach { (k, v) -> put(k, v) } })
  }) + "\n"

  companion object {
    private val JSON = Json { prettyPrint = true; encodeDefaults = false }

    /**
     * The template [bytes] are, captured for [target] by any creation API: its reads and emits are
     * read from the document itself, so they can't drift from what the template does. [source]
     * goes into the sidecar (`tool` is `capture` unless it names another), and so do [fonts], the
     * families the document names: a family in `fonts/fonts.json` downloads with the template.
     */
    fun of(bytes: ByteString, target: Target, source: Map<String, String> = emptyMap(), fonts: Collection<String> = emptyList()): CapturedTemplate {
      val facts = requireNotNull(TemplateInspector.facts(bytes)) { "the capture isn't a Remote Compose document" }
      return CapturedTemplate(bytes, target, facts.declaredReads.sorted(), facts.emits.sorted(), mapOf("tool" to "capture") + source, fonts.distinct().sorted())
    }
  }
}
