package ee.schimke.livery.uibuilder.capture

import ee.schimke.livery.capture.CapturedTemplate
import ee.schimke.livery.capture.CatalogContracts
import ee.schimke.livery.capture.cmp.LiveryCapture
import ee.schimke.livery.capture.cmp.LiveryValues
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.Design
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.Target
import java.io.File
import java.util.ServiceLoader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * Captures Compose UI Builder documents as Livery templates on the JVM, with no UI Builder server:
 * each `.uid` drawn by [UiDesign] with the app's [UiBuilderCatalog]s, its host inputs bound to the
 * design's contract.
 *
 * In a catalog source tree, a template may be a `.uid` beside the design's `design.json`:
 *
 * ```
 * designs/kitchen-tile/design.json        { "id": "kitchen-tile", "contract": "live/status-tile@1", … }
 * designs/kitchen-tile/in-app.uid          the UI Builder document
 * designs/kitchen-tile/launcher.uid
 * designs/kitchen-tile/launcher.target.json   its Target; in-app (AndroidX, level 7) without one
 * ```
 *
 * [captureTree] writes the tree with each `.uid` captured as `<name>.rc` and its sidecar
 * (`source: {tool: ui-builder, design, revision}`), which `livery build` then builds and checks like
 * any other.
 */
object UiBuilderCapture {
  /** A template's target when no `<name>.target.json` names one: an app's own player. */
  val DEFAULT_TARGET: Target = Target(Surfaces.IN_APP, 7, listOf("androidx"))

  /** Every [UiBuilderCatalog] registered on [loader]'s classpath. */
  fun catalogs(loader: ClassLoader = UiBuilderCapture::class.java.classLoader): List<UiBuilderCatalog> =
    ServiceLoader.load(UiBuilderCatalog::class.java, loader).toList()

  /**
   * The design in [document] (`.uid` text) as it is drawn with [catalogs]: those for its catalog
   * (or for any), each preparing the document in turn, their components merged in order.
   */
  fun design(document: String, catalogs: List<UiBuilderCatalog>): UiDesign {
    val parsed = UiDesign.parse(document)
    val fitting = fitting(parsed, catalogs)
    val prepared = fitting.fold(parsed.document) { doc, c -> c.prepare(doc) }
    return UiDesign(prepared, fitting.fold(emptyMap()) { all, c -> all + c.components })
  }

  /** The [catalogs] that draw [design]: those for its catalog, and those for any. */
  fun fitting(design: UiDesign, catalogs: List<UiBuilderCatalog>): List<UiBuilderCatalog> =
    catalogs.filter { it.systemIds.isEmpty() || design.systemId in it.systemIds }

  /**
   * [design] captured for [target] at its own size, its host inputs read from [contract]; [fonts]
   * are the font families it names, for its sidecar (its catalogs' [UiBuilderCatalog.fonts]).
   */
  suspend fun capture(
    design: UiDesign,
    contract: Contract,
    target: Target = DEFAULT_TARGET,
    fonts: Collection<String> = emptyList(),
  ): CapturedTemplate {
    val source = buildMap {
      put("tool", "ui-builder")
      put("design", design.id)
      design.revision?.let { put("revision", it.toString()) }
    }
    return LiveryCapture.capture(target, design.widthDp, design.heightDp, source = source, fonts = fonts) {
      design.Render(LiveryValues(contract))
    }
  }

  /**
   * [source], a catalog source tree, into [out] with every `designs/<id>/<name>.uid` captured as
   * `<name>.rc` and `<name>.json`; everything else is copied as it is. A source holding catalogs
   * (`<source>/<name>/catalog.json`, no `catalog.json` of its own) is captured catalog by catalog
   * into `<out>/<name>/`. Returns the templates captured, as `<catalog>/<design>/<name>`.
   */
  fun captureTree(source: File, out: File, catalogs: List<UiBuilderCatalog> = catalogs()): List<String> {
    require(source.isDirectory) { "no catalog source tree at $source" }
    if (!File(source, "catalog.json").isFile) {
      val trees = source.listFiles().orEmpty().filter { File(it, "catalog.json").isFile }.sortedBy { it.name }
      require(trees.isNotEmpty()) { "$source holds no catalog.json, nor catalogs that do" }
      return trees.flatMap { tree -> captureOne(tree, File(out, tree.name), catalogs).map { "${tree.name}/$it" } }
    }
    return captureOne(source, out, catalogs)
  }

  private fun captureOne(source: File, out: File, catalogs: List<UiBuilderCatalog>): List<String> {
    source.walkTopDown().filter { it.isFile && !it.isTemplateSource() }.forEach {
      it.copyTo(File(out, it.relativeTo(source).path), overwrite = true)
    }
    val captured = mutableListOf<String>()
    for (designDir in File(source, "designs").listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }) {
      val documents = designDir.listFiles { f -> f.extension == "uid" }.orEmpty().sortedBy { it.name }
      if (documents.isEmpty()) continue
      val designFile = File(designDir, "design.json")
      require(designFile.isFile) { "${designDir.name} has .uid templates but no design.json naming its contract" }
      val design = JSON.decodeFromString(Design.serializer(), designFile.readText())
      val contract = CatalogContracts.contract(source, design.contract)
      for (document in documents) {
        val name = document.nameWithoutExtension
        require(NAME.matches(name) && name !in RESERVED) { "${designDir.name}/${document.name}: a template name is ${NAME.pattern}, not design, previews or assets" }
        require(!File(designDir, "$name.rc").exists()) { "${designDir.name}/$name is both a .uid and a .rc" }
        val targetFile = File(designDir, "$name.target.json")
        val target = if (targetFile.isFile) JSON.decodeFromString(Target.serializer(), targetFile.readText()) else DEFAULT_TARGET
        val uiDesign = design(document.readText(), catalogs)
        val fonts = fitting(uiDesign, catalogs).flatMap { it.fonts }
        val problems = uiDesign.validate()
        require(problems.isEmpty()) { "${designDir.name}/${document.name} can't be drawn: ${problems.joinToString("; ")}" }
        val template = try {
          runBlocking { capture(uiDesign, contract, target, fonts) }
        } catch (e: IllegalArgumentException) {
          throw IllegalArgumentException("${designDir.name}/${document.name}: ${e.message}", e)
        }
        val dir = File(out, "designs/${designDir.name}").apply { mkdirs() }
        FileSystem.SYSTEM.write(File(dir, "$name.rc").toOkioPath()) { write(template.bytes) }
        File(dir, "$name.json").writeText(template.sidecar())
        captured += "${designDir.name}/$name"
      }
    }
    return captured
  }

  private fun File.isTemplateSource() = extension == "uid" || name.endsWith(".target.json")

  private val JSON = Json { ignoreUnknownKeys = true }
  private val NAME = Regex("[a-z0-9][a-z0-9._-]{0,63}")
  private val RESERVED = setOf("design", "previews", "assets")
}

/**
 * The capture entry point `livery-gradle-plugin` runs for `capture { uiBuilder() }`: `<source>
 * <out>`, [UiBuilderCapture.captureTree] with the catalogs on the classpath.
 */
fun main(args: Array<String>) {
  require(args.size == 2) { "usage: <catalog source tree> <out>" }
  require(args[0].isNotEmpty()) { "UI Builder capture needs a catalog source tree (livery.source) holding the .uid templates" }
  val catalogs = UiBuilderCapture.catalogs(Thread.currentThread().contextClassLoader ?: UiBuilderCapture::class.java.classLoader)
  val captured = UiBuilderCapture.captureTree(File(args[0]), File(args[1]), catalogs)
  println("captured ${captured.size} UI Builder template(s) with ${catalogs.size} catalog(s): ${captured.joinToString()}")
}
