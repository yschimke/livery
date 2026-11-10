package ee.schimke.livery.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import ee.schimke.composeai.rcplayer.compose.RcFontVariations
import ee.schimke.composeai.rcplayer.compose.RcImageLoader
import ee.schimke.composeai.rcplayer.compose.RcTypefaceLoader
import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.livery.client.BindResult
import ee.schimke.livery.client.Bindings
import ee.schimke.livery.client.ContractBinder
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.Design
import ee.schimke.livery.protocol.NamedValue
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.Target
import ee.schimke.livery.protocol.ThemePack
import ee.schimke.livery.publisher.FontFile
import ee.schimke.livery.publisher.TemplateSpec
import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** One picture of a template: drawn with one of its contract's examples, in one [mode]. */
@Serializable
data class CatalogPreview(
  val design: String,
  val template: String,
  val surface: String,
  val example: String,
  /** `light`, `dark` (the player's own themes) or a theme pack's id. */
  val mode: String,
  /** Relative to the index: `<design>/<template>-<example>-<mode>.png`. */
  val file: String,
  val widthDp: Int,
  val heightDp: Int,
)

/** Something a preview couldn't be drawn for: an example that doesn't bind, a missing contract. */
@Serializable
data class PreviewProblem(val design: String, val template: String?, val example: String?, val message: String)

@Serializable
data class PreviewIndex(val previews: List<CatalogPreview>, val problems: List<PreviewProblem>)

/**
 * Every template in a catalog source tree, drawn the way it will be seen, with nothing written by
 * hand: for each design's templates, each of its contract's examples, in each mode its target
 * allows, at the size it was made for, over the surface it sits on. What the tree already says
 * decides all of it:
 *
 * - **Data**: the contract's `examples`, each bound through [ContractBinder], the device's gate. An
 *   example that doesn't bind is a [PreviewProblem], not a picture.
 * - **Modes**: a `host`-themed template in `light` and `dark` and under each of the tree's theme
 *   packs (each JSON file in `themes/`, in its own mode); a `baked:<mode>` template once, in that mode.
 * - **Size**: the document's own (the size it was captured at), kept inside its target's range;
 *   a document without one gets its surface's usual size.
 * - **Background**: the theme pack's `theme.surface`, else the mode's surface (black on a watch): a
 *   template without a background of its own draws over its host's, never over nothing.
 * - **Fonts** from `fonts/fonts.json`; **images** as one placeholder, so previews never touch the
 *   network.
 *
 * The renders are Compose Multiplatform's player on Skia ([TemplateRenderer]). Writes
 * `<design>/<template>-<example>-<mode>.png`, `previews.json` ([PreviewIndex]) and an `index.html`
 * that shows them all.
 */
object CatalogPreviews {
  private val json = Json { ignoreUnknownKeys = true }

  fun render(source: File, out: File, density: Float = 2f): PreviewIndex {
    require(source.resolve("designs").isDirectory) { "$source has no designs/: not a catalog source tree" }
    out.deleteRecursively()
    out.mkdirs()
    val contracts = source.resolve("contracts").jsonFiles().map { json.decodeFromString(Contract.serializer(), it.readText()) }
    val packs = source.resolve("themes").jsonFiles().map { json.decodeFromString(ThemePack.serializer(), it.readText()) }
    val typefaces = fonts(source.resolve("fonts"))
    val previews = mutableListOf<CatalogPreview>()
    val problems = mutableListOf<PreviewProblem>()
    source.resolve("designs").listFiles().orEmpty().filter { it.resolve("design.json").isFile }.sortedBy { it.name }.forEach { dir ->
      val design = json.decodeFromString(Design.serializer(), dir.resolve("design.json").readText())
      val contract = contracts.filter { it.ref == design.contract }.maxByOrNull { it.minor }
      if (contract == null) {
        problems += PreviewProblem(design.id, null, null, "no contract ${design.contract} in contracts/")
        return@forEach
      }
      val examples = contract.examples.ifEmpty { mapOf("empty" to JsonObject(emptyMap())) }
      dir.listFiles().orEmpty().filter { it.name.endsWith(".rc") }.sortedBy { it.name }.forEach templates@{ rc ->
        val name = rc.name.removeSuffix(".rc")
        val sidecar = dir.resolve("$name.json").takeIf { it.isFile } ?: return@templates
        val target = json.decodeFromString(TemplateSpec.serializer(), sidecar.readText()).target
        val bytes = rc.readBytes()
        val header = runCatching { RcDocumentCodec.decode(bytes, RcWireLimits()).header }.getOrElse {
          problems += PreviewProblem(design.id, name, null, "not a Remote Compose document this player can read: ${it.message}")
          return@templates
        }
        val (width, height) = size(header.width to header.height, target)
        examples.forEach { (example, data) ->
          val bindings = when (val r = ContractBinder.bind(contract, data)) {
            is BindResult.Ok -> r.bindings
            is BindResult.Invalid -> {
              problems += PreviewProblem(design.id, name, example, r.problems.joinToString { "${it.name}: ${it.message}" })
              return@forEach
            }
          }
          modes(target, packs).forEach { mode ->
            val file = "${design.id}/$name-$example-${mode.name}.png"
            val spec = RenderSpec(width, height, density, mode.dark, background = mode.background(target))
            runCatching { TemplateRenderer.png(bytes, bindings + mode.theme, spec, PlaceholderImages, typefaces) }
              .onSuccess { png ->
                out.resolve(file).apply { parentFile.mkdirs() }.writeBytes(png)
                previews += CatalogPreview(design.id, name, target.surface, example, mode.name, file, width, height)
              }
              .onFailure { problems += PreviewProblem(design.id, name, example, "${mode.name}: ${it.message}") }
          }
        }
      }
    }
    return PreviewIndex(previews, problems).also { index ->
      out.resolve("previews.json").writeText(Json { prettyPrint = true }.encodeToString(PreviewIndex.serializer(), index))
      out.resolve("index.html").writeText(html(index))
    }
  }

  /** One way a template is themed in a preview. */
  private class Mode(val name: String, val dark: Boolean, val theme: Bindings) {
    fun background(target: Target): Long =
      (theme.values["theme.surface"] as? NamedValue.Color)?.argb
        ?: when {
          !dark -> 0xFFFFFFFF
          target.surface == Surfaces.WEAR_WIDGET || target.surface == Surfaces.WEAR_APP -> 0xFF000000
          else -> 0xFF121212
        }
  }

  private fun modes(target: Target, packs: List<ThemePack>): List<Mode> = when (target.theme) {
    Target.baked(dark = true) -> listOf(Mode("dark", true, Bindings.EMPTY))
    Target.baked(dark = false) -> listOf(Mode("light", false, Bindings.EMPTY))
    else -> listOf(Mode("light", false, Bindings.EMPTY), Mode("dark", true, Bindings.EMPTY)) +
      packs.map { Mode(it.id, it.mode == "dark", Bindings(it.values)) }
  }

  /** The document's own size ([own], its header's), inside its target's range; else its surface's usual size. */
  internal fun size(own: Pair<Int, Int>, target: Target): Pair<Int, Int> {
    val (w, h) = own.takeIf { it.first > 0 && it.second > 0 } ?: when (target.surface) {
      Surfaces.WEAR_WIDGET -> if (target.container == "wear:small") 192 to 96 else 192 to 192
      Surfaces.LAUNCHER_WIDGET -> 200 to 200
      else -> 360 to 200
    }
    val r = target.size
    return w.coerceIn(r.minWidthDp ?: 1, r.maxWidthDp ?: 4096) to h.coerceIn(r.minHeightDp ?: 1, r.maxHeightDp ?: 4096)
  }

  /** The tree's `fonts/fonts.json` faces, by family (case-insensitively, as the player asks), then the defaults. */
  private fun fonts(dir: File): RcTypefaceLoader {
    val files = dir.resolve("fonts.json").takeIf { it.isFile }
      ?.let { json.decodeFromString(ListSerializer(FontFile.serializer()), it.readText()) }.orEmpty()
    val families = files.groupBy { it.family }.mapValues { (_, faces) ->
      FontFamily(faces.map { Font(dir.resolve(it.file), FontWeight(it.weight), if (it.italic) FontStyle.Italic else FontStyle.Normal) })
    }
    return object : RcTypefaceLoader {
      override val families: Set<String> get() = families.keys + RcTypefaceLoader.Default.families
      override fun typeface(family: String, variations: RcFontVariations?): FontFamily? =
        families.entries.firstOrNull { it.key.equals(family, ignoreCase = true) }?.value
          ?: RcTypefaceLoader.Default.typeface(family, variations)
    }
  }

  /** Every image is the same neutral placeholder: a preview never touches the network. */
  private object PlaceholderImages : RcImageLoader {
    private val placeholder by lazy {
      ImageBitmap(160, 160).also { bitmap ->
        Canvas(bitmap).apply {
          drawRect(0f, 0f, 160f, 160f, Paint().apply { color = Color(0xFF5B6B7F) })
          drawCircle(Offset(80f, 62f), 30f, Paint().apply { color = Color(0xFFE8EDF3) })
          drawCircle(Offset(80f, 160f), 58f, Paint().apply { color = Color(0xFFE8EDF3) })
        }
      }
    }

    override suspend fun load(data: RcBitmapData): ImageBitmap = placeholder
  }

  private fun html(index: PreviewIndex): String = buildString {
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    append("<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width\"><title>Catalog previews</title>")
    append("<style>body{font:14px system-ui,sans-serif;margin:16px;background:#eee}h2{margin:24px 0 8px}h3{margin:12px 0 4px;font-size:13px;color:#555}")
    append(".row{display:flex;flex-wrap:wrap;gap:12px}figure{margin:0}figcaption{font-size:11px;color:#666}img{display:block;max-width:100%;border:1px solid #ccc}</style>")
    append("<h1>Catalog previews</h1>")
    if (index.problems.isNotEmpty()) {
      append("<h2>Problems</h2><ul>")
      index.problems.forEach { append("<li>${esc(listOfNotNull(it.design, it.template, it.example).joinToString("/"))}: ${esc(it.message)}</li>") }
      append("</ul>")
    }
    index.previews.groupBy { it.design }.forEach { (design, byDesign) ->
      append("<h2>${esc(design)}</h2>")
      byDesign.groupBy { it.template }.forEach { (template, byTemplate) ->
        append("<h3>${esc(template)} · ${esc(byTemplate.first().surface)} · ${byTemplate.first().widthDp}×${byTemplate.first().heightDp} dp</h3><div class=row>")
        byTemplate.forEach {
          append("<figure><img src=\"${esc(it.file)}\" width=${it.widthDp} alt=\"${esc("$design $template ${it.example} ${it.mode}")}\">")
          append("<figcaption>${esc(it.example)} · ${esc(it.mode)}</figcaption></figure>")
        }
        append("</div>")
      }
    }
  }

  private fun File.jsonFiles(): List<File> = listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json") }.sortedBy { it.name }
}

/**
 * `<source> <out>`: renders [source]'s previews into [out] ([CatalogPreviews.render]), and exits 1
 * when any example couldn't be drawn. `renderLiveryPreviews` runs this in its own JVM.
 */
fun main(args: Array<String>) {
  require(args.size == 2) { "usage: <catalog source tree> <out>" }
  val out = File(args[1])
  val index = CatalogPreviews.render(File(args[0]), out)
  println("previews: ${index.previews.size} in ${out.resolve("index.html").absolutePath}")
  index.problems.forEach { System.err.println("${listOfNotNull(it.design, it.template, it.example).joinToString("/")}: ${it.message}") }
  if (index.problems.isNotEmpty()) exitProcess(1)
}
