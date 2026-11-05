package ee.schimke.livery.publisher

import ee.schimke.livery.instantiate.TemplateInspector

import ee.schimke.livery.client.BindResult
import ee.schimke.livery.client.ContractBinder
import ee.schimke.livery.client.JavaCrypto
import ee.schimke.livery.protocol.AssetEntry
import ee.schimke.livery.protocol.AssetKind
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.FontFace
import ee.schimke.livery.protocol.ImageProxy
import ee.schimke.livery.protocol.Design
import ee.schimke.livery.protocol.Manifest
import ee.schimke.livery.protocol.PlacementRule
import ee.schimke.livery.protocol.Preview
import ee.schimke.livery.protocol.Target
import ee.schimke.livery.protocol.TemplateEntry
import ee.schimke.livery.protocol.ThemePack
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Catalog settings, `catalog.json` at the root of a source tree:
 *
 * ```
 * catalog.json
 * contracts/<any>.json              Contract
 * designs/<designId>/design.json    Design (previews filled in from previews/)
 * designs/<designId>/<name>.rc      a values-free document, from any authoring tool
 * designs/<designId>/<name>.json    TemplateSpec: its target and tags
 * designs/<designId>/previews/<theme>[~<example>].png|webp
 * designs/<designId>/assets/<name>.png|webp|svg   images its templates need, named <name>
 * fonts/fonts.json                  FontFile list: the catalog's typefaces and their licences
 * placements/<any>.json             PlacementRule (local growth rules)
 * themes/<any>.json                 ThemePack
 * ```
 */
@Serializable
data class CatalogConfig(
  val catalog: String,
  val channel: String = "main",
  val ttlSeconds: Long = 3600,
  val expiresInDays: Long = 14,
  val minClient: Int = 0,
  /** How remote images in datasets are fetched (PROTOCOL.md §6.2); written into the manifest. */
  val imageProxy: ImageProxy? = null,
)

/** The sidecar beside each `.rc`: what it was built for. */
@Serializable
data class TemplateSpec(
  val target: Target,
  val tags: Map<String, String> = emptyMap(),
  /** Named values the document reads, if the authoring tool reported them. */
  val reads: List<String> = emptyList(),
  val emits: List<String> = emptyList(),
  /** Font families it names: a family in `fonts/fonts.json`, `google:<family>` or `system:<family>`. */
  val fonts: List<String> = emptyList(),
  /** Where the document came from (tool, design, revision, commit): copied to the manifest entry. */
  val source: Map<String, String> = emptyMap(),
)

/** One entry of `fonts/fonts.json`: a font file beside it and the face it provides. */
@Serializable
data class FontFile(
  val file: String,
  val family: String,
  val weight: Int = 400,
  val italic: Boolean = false,
  /** SPDX id, required: a catalog redistributes its fonts to every client. */
  val license: String,
)

/** Where the output is hosted, which decides how blobs are laid out and addressed. */
sealed interface Layout {
  /** `manifest.json`, `blobs/<sha>.rc`, `previews/<sha>.<ext>`: any static host or server channel. */
  data object Static : Layout
  /** Everything flat in one directory, addressed by the release's download URL. */
  data class GitHubRelease(val owner: String, val repo: String, val tag: String) : Layout {
    val downloadBase get() = "https://github.com/$owner/$repo/releases/download/$tag/"
  }
}

/** The protocol level that added resources (PROTOCOL.md §2.1). */
const val RESOURCES_LEVEL: Int = 2

class CatalogProblems(val problems: List<String>) : Exception(problems.joinToString("\n", prefix = "catalog has ${problems.size} problem(s):\n"))

/**
 * Builds a catalog from a source tree. Validation is the same gate the client runs: every
 * template's contract exists and matches its design, every example and placement payload binds,
 * every name is unique. Authoring tools only have to produce `.rc` bytes and a sidecar.
 */
class CatalogBuilder(
  private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = false; encodeDefaults = false },
  private val now: () -> Long = System::currentTimeMillis,
  /** Optional deep check of a document (op profile, size, named values it reads); returns problems. */
  private val inspect: (bytes: ByteArray, spec: TemplateSpec) -> List<String> = { _, _ -> emptyList() },
) {
  data class Output(val manifest: Manifest, val manifestBytes: ByteArray, val files: Map<String, ByteArray>)

  fun build(src: File, layout: Layout, serial: Long? = null): Output {
    val problems = mutableListOf<String>()
    val config = json.decodeFromString(CatalogConfig.serializer(), File(src, "catalog.json").readText())
    val contracts = jsonFiles(File(src, "contracts")).map { json.decodeFromString(Contract.serializer(), it.readText()) }
    val themes = jsonFiles(File(src, "themes")).map { json.decodeFromString(ThemePack.serializer(), it.readText()) }
    val placements = jsonFiles(File(src, "placements")).map { json.decodeFromString(PlacementRule.serializer(), it.readText()) }
    val files = linkedMapOf<String, ByteArray>()
    val (blobDir, previewDir) = when (layout) {
      Layout.Static -> "blobs/" to "previews/"
      is Layout.GitHubRelease -> "" to ""
    }
    val assetDir = if (layout is Layout.GitHubRelease) "" else "assets/"
    val assets = linkedMapOf<String, AssetEntry>()
    fun addAsset(bytes: ByteArray, entry: (sha: String) -> AssetEntry): AssetEntry {
      val a = entry(JavaCrypto.sha256HexBlocking(bytes))
      files[assetDir + a.fileName] = bytes
      return assets.getOrPut(a.sha256) { a }
    }
    val fontsDir = File(src, "fonts")
    val fonts = File(fontsDir, "fonts.json").takeIf { it.isFile }
      ?.let { json.decodeFromString(ListSerializer(FontFile.serializer()), it.readText()) }.orEmpty()
      .mapNotNull { f ->
        val file = File(fontsDir, f.file)
        val mime = when (file.extension.lowercase()) { "ttf" -> "font/ttf"; "otf" -> "font/otf"; "woff2" -> "font/woff2"; else -> null }
        when {
          !file.isFile -> { problems += "fonts/${f.file}: missing"; null }
          mime == null -> { problems += "fonts/${f.file}: not a ttf, otf or woff2"; null }
          f.license.isBlank() -> { problems += "fonts/${f.file}: no license"; null }
          else -> addAsset(file.readBytes()) { sha -> AssetEntry(sha, file.length(), mime, AssetKind.Font, font = FontFace(f.family, f.weight, f.italic), license = f.license) }
        }
      }

    contracts.groupBy { it.ref to it.minor }.filter { it.value.size > 1 }.forEach { problems += "duplicate contract ${it.key}" }
    for (c in contracts) for ((name, example) in c.examples) {
      (ContractBinder.bind(c, example) as? BindResult.Invalid)?.let { problems += "contract ${c.ref} example '$name': ${it.problems}" }
    }
    fun contract(ref: String): Contract? = contracts.filter { it.ref == ref }.maxByOrNull { it.minor }

    val designs = mutableListOf<Design>()
    val templates = mutableListOf<TemplateEntry>()
    for (dir in File(src, "designs").listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }) {
      val designFile = File(dir, "design.json")
      if (!designFile.isFile) { problems += "${dir.name}: no design.json"; continue }
      val design = json.decodeFromString(Design.serializer(), designFile.readText())
      if (design.id != dir.name) problems += "${dir.name}: design.json id '${design.id}' must match its directory"
      if (contract(design.contract) == null) problems += "${design.id}: unknown contract ${design.contract}"

      val previews = File(dir, "previews").listFiles().orEmpty().filter { it.extension in setOf("png", "webp") }.sortedBy { it.name }.map { f ->
        val bytes = f.readBytes()
        val sha = JavaCrypto.sha256HexBlocking(bytes)
        val (w, h) = imageSize(bytes) ?: (0 to 0)
        val theme = f.nameWithoutExtension.substringBefore('~')
        val example = f.nameWithoutExtension.substringAfter('~', "").ifEmpty { null }
        val p = Preview(sha, if (f.extension == "png") "image/png" else "image/webp", w, h, theme, example)
        files[previewDir + p.fileName] = bytes
        p
      }
      designs += design.copy(previews = design.previews + previews)

      val images = File(dir, "assets").listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.mapNotNull { f ->
        val mime = when (f.extension.lowercase()) { "png" -> "image/png"; "webp" -> "image/webp"; "svg" -> "image/svg+xml"; "jpg", "jpeg" -> "image/jpeg"; else -> null }
        if (mime == null) { problems += "${design.id}/assets/${f.name}: not an image"; null }
        else addAsset(f.readBytes()) { sha -> AssetEntry(sha, f.length(), mime, AssetKind.Image, name = f.nameWithoutExtension) }
      }

      val rcs = dir.listFiles().orEmpty().filter { it.extension == "rc" }.sortedBy { it.name }
      if (rcs.isEmpty()) problems += "${design.id}: no .rc templates"
      for (rc in rcs) {
        val specFile = File(dir, rc.nameWithoutExtension + ".json")
        if (!specFile.isFile) { problems += "${design.id}/${rc.name}: no ${specFile.name} sidecar"; continue }
        val spec = json.decodeFromString(TemplateSpec.serializer(), specFile.readText())
        val bytes = rc.readBytes()
        inspect(bytes, spec).forEach { problems += "${design.id}/${rc.name}: $it" }
        contract(design.contract)?.let { c ->
          val undeclared = spec.reads.filterNot { r -> declares(c, r) }
          if (undeclared.isNotEmpty()) problems += "${design.id}/${rc.name}: reads values outside ${c.ref}: $undeclared"
          val actions = spec.emits.filterNot { it in c.actions }
          if (actions.isNotEmpty()) problems += "${design.id}/${rc.name}: emits undeclared actions $actions"
        }
        val sha = JavaCrypto.sha256HexBlocking(bytes)
        files["$blobDir$sha.rc"] = bytes
        val fontAssets = spec.fonts.flatMap { family ->
          when {
            family.startsWith("google:") || family.startsWith("system:") -> emptyList()
            else -> fonts.filter { it.font?.family == family }.ifEmpty {
              problems += "${design.id}/${rc.name}: font '$family' is not in fonts/fonts.json (or use google:/system:)"
              emptyList()
            }
          }
        }
        templates += TemplateEntry(
          sha, bytes.size.toLong(), design.id, design.contract, spec.target, spec.tags,
          reads = spec.reads, emits = spec.emits,
          assets = (images + fontAssets).map { it.sha256 }.distinct(), fonts = spec.fonts,
          source = spec.source,
        )
      }
    }
    designs.groupBy { it.id }.filter { it.value.size > 1 }.forEach { problems += "duplicate design ${it.key}" }
    templates.groupBy { it.designId to it.target }.filter { it.value.size > 1 }.forEach { problems += "${it.key.first}: two templates for one target ${it.key.second}" }

    for (p in placements) {
      val designId = p.template.designId
      val design = designs.firstOrNull { it.id == designId }
      if (designId != null && design == null) problems += "placement ${p.id}: unknown design $designId"
      val c = contract(p.template.contract ?: design?.contract ?: "")
      if (c == null) problems += "placement ${p.id}: no contract to check its data against"
      else (ContractBinder.bind(c, p.data) as? BindResult.Invalid)?.let { problems += "placement ${p.id}: ${it.problems}" }
    }

    if (problems.isNotEmpty()) throw CatalogProblems(problems)

    val t = now()
    val manifest = Manifest(
      catalog = config.catalog,
      channel = config.channel,
      serial = serial ?: t,
      generatedAtEpochMs = t,
      expiresAtEpochMs = t + config.expiresInDays * 86_400_000,
      ttlSeconds = config.ttlSeconds,
      // A template that needs assets must not reach a client that would play it without them.
      minClient = maxOf(config.minClient, if (templates.any { it.assets.isNotEmpty() }) RESOURCES_LEVEL else 0),
      blobBase = if (layout is Layout.GitHubRelease) layout.downloadBase else "blobs/",
      previewBase = if (layout is Layout.GitHubRelease) layout.downloadBase else "previews/",
      contracts = contracts,
      designs = designs,
      templates = templates,
      themes = themes,
      placements = placements,
      assetBase = if (layout is Layout.GitHubRelease) layout.downloadBase else "assets/",
      assets = assets.values.toList(),
      imageProxy = config.imageProxy,
    )
    val bytes = json.encodeToString(Manifest.serializer(), manifest).encodeToByteArray()
    return Output(manifest, bytes, files)
  }

  /**
   * Whether [read] is a value [ContractBinder] can produce for one of the contract's lists:
   * `<list>.count`, or `<list>.<index>.<field>` with the index below the list's `max` and the field
   * one of its item's fields. Anything else (a typo, `-1`, an index past `max`) is never bound.
   */
  private fun readsListValue(c: Contract, read: String): Boolean = Companion.readsListValue(c, read)

  companion object {
    /** Whether [read] is a value a template may read under [c]: an input, a list value or a theme role. */
    fun declares(c: Contract, read: String): Boolean = read in c.inputs || readsListValue(c, read) || read.startsWith("theme.")

    /** The inspect hook `livery build --inspect` and agent drafts use: [TemplateInspector]. */
    val INSPECT: (ByteArray, TemplateSpec) -> List<String> = { bytes, spec -> TemplateInspector.problems(bytes, spec.reads, spec.emits) }

    private fun readsListValue(c: Contract, read: String): Boolean = c.lists.any { (name, list) ->
    if (read == "$name.count") return@any true
    if (!read.startsWith("$name.")) return@any false
    val rest = read.removePrefix("$name.")
    val index = rest.substringBefore('.').toIntOrNull() ?: return@any false
    val field = rest.substringAfter('.', "")
    rest.substringBefore('.') == index.toString() && index in 0 until list.max && field in list.item
  }
  }

  private fun jsonFiles(dir: File) = dir.listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.name }

  /** PNG IHDR or WebP VP8/VP8L/VP8X dimensions, without an image library. */
  private fun imageSize(b: ByteArray): Pair<Int, Int>? {
    fun be(i: Int) = ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
    fun le16(i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    fun le24(i: Int) = le16(i) or ((b[i + 2].toInt() and 0xFF) shl 16)
    return when {
      b.size > 24 && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() -> be(16) to be(20)
      b.size > 30 && String(b, 8, 4) == "WEBP" -> when (String(b, 12, 4)) {
        "VP8 " -> (le16(26) and 0x3FFF) to (le16(28) and 0x3FFF)
        "VP8L" -> { val v = be(21).let { Integer.reverseBytes(it) }; ((v and 0x3FFF) + 1) to (((v shr 14) and 0x3FFF) + 1) }
        "VP8X" -> (le24(24) + 1) to (le24(27) + 1)
        else -> null
      }
      else -> null
    }
  }
}
