package ee.schimke.livery.publisher

import okio.FileSystem
import okio.Path
import ee.schimke.livery.instantiate.TemplateInspector
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.Design
import ee.schimke.livery.protocol.Target
import kotlinx.serialization.json.Json

/**
 * Puts a document from any authoring tool into a catalog source tree as
 * `designs/<designId>/<name>.rc` and its sidecar. The sidecar's reads and emits are read from the
 * document itself, so a hand-written sidecar can't drift from what the template does, and the
 * document is checked against its design's contract (names and types) before anything is written.
 * The whole tree is then built (as `livery build` does); when that fails the import is undone.
 */
object TemplateImport {
  /** A design's folder and template names: what a file name and a manifest id can both hold. */
  val NAME: Regex = Regex("[a-z0-9][a-z0-9._-]{0,63}")

  /** Template names whose files would be a design folder's own: `design.json`, `previews/`, `assets/`. */
  val RESERVED: Set<String> = setOf("design", "previews", "assets")

  data class Imported(val rc: Path, val sidecar: Path, val spec: TemplateSpec, val contract: String)

  fun into(fileSystem: FileSystem, src: Path, designId: String, name: String, document: ByteArray, target: Target, source: Map<String, String> = emptyMap()): Imported =
    into(Node(fileSystem, src), designId, name, document, target, source)

  internal fun into(src: Node, designId: String, name: String, document: ByteArray, target: Target, source: Map<String, String> = emptyMap()): Imported {
    if (!NAME.matches(designId) || !NAME.matches(name)) throw CatalogProblems(listOf("design and template names are ${NAME.pattern}: '$designId/$name'"))
    if (name in RESERVED) throw CatalogProblems(listOf("'$name' is reserved in a design's folder (${RESERVED.sorted()}): pick another template name"))
    val dir = src.resolve("designs").resolve(designId)
    val designFile = dir.resolve("design.json")
    if (!designFile.isFile) throw CatalogProblems(listOf("no design $designId: create $designFile first (its id, contract, title and install policy)"))
    val design = JSON.decodeFromString(Design.serializer(), designFile.readText())
    val contract = contract(src, design.contract)
    val facts = TemplateInspector.facts(document) ?: throw CatalogProblems(listOf("not a Remote Compose document"))
    val problems = buildList {
      val reads = facts.declaredReads.filterNot { CatalogBuilder.declares(contract, it) }
      if (reads.isNotEmpty()) add("reads values ${contract.ref} doesn't declare: ${reads.sorted()}")
      val emits = facts.emits.filterNot { it in contract.actions }
      if (emits.isNotEmpty()) add("emits actions ${contract.ref} doesn't declare: ${emits.sorted()}")
      addAll(TemplateInspector.typeProblems(document, contract))
    }
    if (problems.isNotEmpty()) throw CatalogProblems(problems.map { "$designId/$name: $it" })

    val spec = TemplateSpec(target, reads = facts.declaredReads.sorted(), emits = facts.emits.sorted(), source = source)
    val rc = dir.resolve("$name.rc")
    val sidecar = dir.resolve("$name.json")
    val before = rc.takeIf { it.isFile }?.readBytes() to sidecar.takeIf { it.isFile }?.readBytes()
    rc.writeBytes(document)
    sidecar.writeText(PRETTY.encodeToString(TemplateSpec.serializer(), spec) + "\n")
    try {
      CatalogBuilder().build(src, Layout.Static)
    } catch (e: Exception) {
      // Any failure, a refused catalog or a file that no longer decodes, leaves the tree as it was.
      restore(rc, before.first); restore(sidecar, before.second)
      throw e
    }
    return Imported(rc.path, sidecar.path, spec, contract.ref)
  }

  /** The newest minor of [ref] (`id@major`) in [src]'s `contracts/`. */
  fun contract(fileSystem: FileSystem, src: Path, ref: String): Contract = contract(Node(fileSystem, src), ref)

  internal fun contract(src: Node, ref: String): Contract =
    src.resolve("contracts").listFiles().orEmpty().filter { it.extension == "json" }
      .map { JSON.decodeFromString(Contract.serializer(), it.readText()) }
      .filter { it.ref == ref }.maxByOrNull { it.minor }
      ?: throw CatalogProblems(listOf("no contract $ref in $src/contracts"))

  private fun restore(f: Node, bytes: ByteArray?) { if (bytes == null) f.delete() else f.writeBytes(bytes) }

  private val JSON = Json { ignoreUnknownKeys = false }
  private val PRETTY = Json { prettyPrint = true; encodeDefaults = false }
}
