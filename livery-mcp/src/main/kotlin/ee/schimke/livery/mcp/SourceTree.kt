package ee.schimke.livery.mcp

import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.Design
import ee.schimke.livery.publisher.TemplateSpec
import java.io.File
import kotlinx.serialization.json.Json

/**
 * A catalog's source tree as `livery build` reads it (DESIGN.md "Authoring and hosting are
 * plugins"), read afresh on every call so an agent always sees the files as they are now.
 */
class SourceTree(val root: File) {
  private val json = Json { ignoreUnknownKeys = false }

  data class Template(val name: String, val rc: File, val spec: TemplateSpec)

  data class DesignDir(val design: Design, val dir: File, val templates: List<Template>)

  fun contracts(): List<Contract> =
    jsonFiles(File(root, "contracts")).map { json.decodeFromString(Contract.serializer(), it.readText()) }

  /** The newest minor of [ref] (`id@major`). */
  fun contract(ref: String): Contract? = contracts().filter { it.ref == ref }.maxByOrNull { it.minor }

  fun designs(): List<DesignDir> =
    File(root, "designs").listFiles().orEmpty().filter { File(it, "design.json").isFile }.sortedBy { it.name }.map { dir ->
      val design = json.decodeFromString(Design.serializer(), File(dir, "design.json").readText())
      val templates = dir.listFiles().orEmpty().filter { it.extension == "rc" }.sortedBy { it.name }.mapNotNull { rc ->
        val sidecar = File(dir, rc.nameWithoutExtension + ".json").takeIf { it.isFile } ?: return@mapNotNull null
        Template(rc.nameWithoutExtension, rc, json.decodeFromString(TemplateSpec.serializer(), sidecar.readText()))
      }
      DesignDir(design, dir, templates)
    }

  fun design(id: String): DesignDir? = designs().firstOrNull { it.design.id == id }

  private fun jsonFiles(dir: File) = dir.listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.name }

  companion object {
    /** A design id or template name: one path segment of letters, digits, `-`, `_` and `.`. */
    private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    fun safeName(name: String): Boolean = NAME.matches(name) && ".." !in name
  }
}
