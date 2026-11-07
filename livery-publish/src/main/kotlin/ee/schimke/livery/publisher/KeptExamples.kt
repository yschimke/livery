package ee.schimke.livery.publisher

import ee.schimke.livery.protocol.Contract
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A contract's earlier preview data, kept beside it so every template is still checked with it.
 *
 * A contract's `examples` are the data templates are previewed, reviewed and verified with. When one
 * changes or goes, the old data doesn't stop existing: an installed app may still send data shaped
 * like it. So the old data stays in the source tree, under
 * `contracts/examples/<contract file name>/<example>-<hash>.json`, one file per distinct version
 * (the hash is of its data, so the same data is kept once), and the review, previews and
 * `livery-verify` play every template with it as well as with the current examples. `livery check`
 * fails while the base had example data that is neither current nor kept; `livery keep-examples`
 * keeps it. The catalog build ignores the folder: kept examples are for checking, not for apps.
 */
object KeptExamples {
  const val DIR: String = "examples"

  /** An example the base had that this tree neither has nor keeps. */
  data class Missing(val contract: String, val file: String, val name: String, val data: JsonObject)

  /** The kept examples of [src], by contract ref, each labelled `<example> (kept <hash>)`. */
  fun load(src: File): Map<String, Map<String, JsonObject>> {
    val byFile = contractFiles(src)
    return byFile.mapNotNull { (stem, contract) ->
      val kept = kept(src, stem).associate { (name, data) -> "$name (kept ${hash(data)})" to data }
      kept.takeIf { it.isNotEmpty() }?.let { contract.ref to it }
    }.groupBy({ it.first }, { it.second }).mapValues { (_, maps) -> maps.reduce { a, b -> a + b } }
  }

  /**
   * The example data [base] has, current or kept, that [src] neither has (under any name) nor
   * keeps. A contract [src] no longer has at all was removed, and its data with it: not missing.
   */
  fun missing(src: File, base: File): List<Missing> {
    val now = contractFiles(src)
    val have = now.flatMap { (stem, c) ->
      c.examples.values.map { c.ref to hash(it) } + kept(src, stem).map { c.ref to hash(it.second) }
    }.toSet()
    val stemOf = now.associate { (stem, c) -> c.ref to stem }
    return contractFiles(base).filter { (_, c) -> c.ref in stemOf }.flatMap { (stem, c) ->
      (c.examples.toList() + kept(base, stem)).distinctBy { hash(it.second) }
        .filter { (_, data) -> (c.ref to hash(data)) !in have }
        .map { (name, data) -> Missing(c.ref, stemOf.getValue(c.ref), name, data) }
    }
  }

  /** Writes [missing]'s data into [src]'s kept examples; returns the files written. */
  fun keep(src: File, base: File): List<File> = missing(src, base).map { m ->
    File(src, "contracts/$DIR/${m.file}/${safe(m.name)}-${hash(m.data)}.json").apply {
      parentFile.mkdirs()
      writeText(PRETTY.encodeToString(JsonObject.serializer(), m.data) + "\n")
    }
  }

  /** A short, stable hash of an example's data, whatever its key order. */
  fun hash(data: JsonObject): String =
    MessageDigest.getInstance("SHA-256").digest(canonical(data).toString().toByteArray())
      .take(4).joinToString("") { "%02x".format(it) }

  private fun canonical(e: JsonElement): JsonElement = when (e) {
    is JsonObject -> JsonObject(e.entries.sortedBy { it.key }.associate { (k, v) -> k to canonical(v) })
    is JsonArray -> JsonArray(e.map(::canonical))
    else -> e
  }

  /** The examples kept for the contract in `contracts/<stem>.json`, as (example name, data). */
  private fun kept(src: File, stem: String): List<Pair<String, JsonObject>> =
    File(src, "contracts/$DIR/$stem").listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.name }
      .map { it.nameWithoutExtension.substringBeforeLast('-') to Json.parseToJsonElement(it.readText()).jsonObject }

  private fun contractFiles(src: File): List<Pair<String, Contract>> =
    File(src, "contracts").listFiles().orEmpty().filter { it.isFile && it.extension == "json" }.sortedBy { it.name }
      .mapNotNull { f -> runCatching { f.nameWithoutExtension to LENIENT.decodeFromString(Contract.serializer(), f.readText()) }.getOrNull() }

  private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._]"), "_")

  private val LENIENT = Json { ignoreUnknownKeys = true }
  private val PRETTY = Json { prettyPrint = true }
}
