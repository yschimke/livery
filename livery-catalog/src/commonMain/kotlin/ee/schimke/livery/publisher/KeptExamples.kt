package ee.schimke.livery.publisher

import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import ee.schimke.livery.protocol.Contract
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
  fun load(fileSystem: FileSystem, src: Path): Map<String, Map<String, JsonObject>> = load(Node(fileSystem, src))

  internal fun load(src: Node): Map<String, Map<String, JsonObject>> {
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
  fun missing(fileSystem: FileSystem, src: Path, base: Path): List<Missing> = missing(Node(fileSystem, src), Node(fileSystem, base))

  internal fun missing(src: Node, base: Node): List<Missing> {
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
  fun keep(fileSystem: FileSystem, src: Path, base: Path): List<Path> = keep(Node(fileSystem, src), Node(fileSystem, base)).map { it.path }

  internal fun keep(src: Node, base: Node): List<Node> = missing(src, base).map { m ->
    src.resolve("contracts").resolve(DIR).resolve(m.file).resolve("${safe(m.name)}-${hash(m.data)}.json").apply {
      writeText(PRETTY.encodeToString(JsonObject.serializer(), m.data) + "\n")
    }
  }

  /** A short, stable hash of an example's data, whatever its key order. */
  fun hash(data: JsonObject): String =
    sha256Hex(canonical(data).toString().encodeUtf8()).take(8)

  private fun canonical(e: JsonElement): JsonElement = when (e) {
    is JsonObject -> JsonObject(e.entries.sortedBy { it.key }.associate { (k, v) -> k to canonical(v) })
    is JsonArray -> JsonArray(e.map(::canonical))
    else -> e
  }

  /** The examples kept for the contract in `contracts/<stem>.json`, as (example name, data). */
  private fun kept(src: Node, stem: String): List<Pair<String, JsonObject>> =
    src.resolve("contracts").resolve(DIR).resolve(stem).listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.name }
      .map { it.nameWithoutExtension.substringBeforeLast('-') to Json.parseToJsonElement(it.readText()).jsonObject }

  private fun contractFiles(src: Node): List<Pair<String, Contract>> =
    src.resolve("contracts").listFiles().orEmpty().filter { it.isFile && it.extension == "json" }.sortedBy { it.name }
      .mapNotNull { f -> runCatching { f.nameWithoutExtension to LENIENT.decodeFromString(Contract.serializer(), f.readText()) }.getOrNull() }

  private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._]"), "_")

  private val LENIENT = Json { ignoreUnknownKeys = true }
  private val PRETTY = Json { prettyPrint = true }
}
