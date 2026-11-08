package ee.schimke.livery.a2ui

import ee.schimke.livery.protocol.ManifestSignature
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64

/** One mutually exclusive template form, parsed identically by validators and renderers. */
sealed interface A2uiTemplate {
  data class Inline(val base64: String, val signature: ManifestSignature?) : A2uiTemplate
  data class Named(val sha256: String, val url: String?, val signature: ManifestSignature?) : A2uiTemplate
  data class Query(val params: JsonObject) : A2uiTemplate
  data class Invalid(val reason: String) : A2uiTemplate

  companion object {
    fun parse(value: JsonElement): A2uiTemplate {
      val t = value as? JsonObject ?: return Invalid("template must be an object")
      if (listOf("inline", "sha256", "query").count { it in t } != 1) return Invalid("template needs exactly one of inline, sha256 or query")
      fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
      if ("query" in t) {
        if (t.keys != setOf("query")) return Invalid("query cannot include a URL or signature")
        return (t["query"] as? JsonObject)?.let(::Query) ?: Invalid("query must be an object of params")
      }
      val signature = if ("signature" !in t) null else {
        val s = t["signature"] as? JsonObject ?: return Invalid("signature must be an object")
        val alg = if ("alg" in s) s.text("alg") ?: return Invalid("signature alg must be a string") else "Ed25519"
        val key = s.text("keyId") ?: return Invalid("signature needs keyId and signature, as strings")
        val sig = s.text("signature") ?: return Invalid("signature needs keyId and signature, as strings")
        ManifestSignature(alg, key, sig)
      }
      if ("inline" in t) {
        if (t.keys.any { it !in setOf("inline", "signature") }) return Invalid("inline cannot include other template fields")
        val text = t.text("inline") ?: return Invalid("template needs inline: the document as base64")
        if (runCatching { Base64.decode(text) }.isFailure) return Invalid("inline is not base64")
        return Inline(text, signature)
      }
      if (t.keys.any { it !in setOf("sha256", "url", "signature") }) return Invalid("unknown named template field")
      val sha = t.text("sha256") ?: return Invalid("sha256 must be a string")
      if (!Regex("[0-9a-f]{64}").matches(sha)) return Invalid("sha256 must be 64 lowercase hex digits")
      val url = if ("url" in t) t.text("url") ?: return Invalid("url must be a string") else null
      return Named(sha, url, signature)
    }
  }
}
