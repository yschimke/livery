package ee.schimke.livery.mcp

import okio.ByteString
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.Design
import ee.schimke.livery.publisher.TemplateSpec
import ee.schimke.livery.publisher.targetMetadataOrNull
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/**
 * A catalog's source tree as `livery build` reads it (DESIGN.md "Authoring and hosting are
 * plugins"), at [root] on [fileSystem], read afresh on every call so an agent always sees the
 * files as they are now.
 */
class SourceTree(val fileSystem: FileSystem, val root: Path) {
  private val json = Json { ignoreUnknownKeys = false }

  data class Template(val name: String, val rc: Path, val spec: TemplateSpec)

  data class DesignDir(val design: Design, val dir: Path, val templates: List<Template>)

  fun contracts(): List<Contract> =
    jsonFiles(root / "contracts").map { json.decodeFromString(Contract.serializer(), read(it)) }

  /** The newest minor of [ref] (`id@major`). */
  fun contract(ref: String): Contract? = contracts().filter { it.ref == ref }.maxByOrNull { it.minor }

  fun designs(): List<DesignDir> =
    list(root / "designs").filter { isFile(it / "design.json") }.sortedBy { it.name }.map { dir ->
      val design = json.decodeFromString(Design.serializer(), read(dir / "design.json"))
      val templates = list(dir).filter { it.name.endsWith(".rc") && isFile(it) }.sortedBy { it.name }.mapNotNull { rc ->
        val name = rc.name.removeSuffix(".rc")
        val sidecar = (dir / "$name.json").takeIf(::isFile) ?: return@mapNotNull null
        Template(name, rc, json.decodeFromString(TemplateSpec.serializer(), read(sidecar)))
      }
      DesignDir(design, dir, templates)
    }

  fun design(id: String): DesignDir? = designs().firstOrNull { it.design.id == id }

  /** Whether [path] is a file, following a symbolic link as `livery build` does. */
  fun isFile(path: Path): Boolean = fileSystem.targetMetadataOrNull(path)?.isRegularFile == true

  fun read(path: Path): String = fileSystem.read(path) { readUtf8() }

  fun readBytes(path: Path): ByteString = fileSystem.read(path) { readByteString() }

  private fun list(dir: Path): List<Path> = fileSystem.listOrNull(dir).orEmpty()

  private fun jsonFiles(dir: Path) = list(dir).filter { it.name.endsWith(".json") && isFile(it) }.sortedBy { it.name }

  companion object {
    /** A design id or template name: one path segment of letters, digits, `-`, `_` and `.`. */
    private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    fun safeName(name: String): Boolean = NAME.matches(name) && ".." !in name
  }
}
