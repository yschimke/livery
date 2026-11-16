package ee.schimke.livery.capture

import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.ContractDef
import ee.schimke.livery.protocol.Design
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A catalog source tree (`livery build`'s input) written from Kotlin: `catalog.json`, `contracts/`,
 * and each design's folder (`designs/<id>/design.json` beside its templates, [DesignFolder]).
 * Hand-edited files (`themes/`, `placements/`, `experiments/`, `fonts/`) are left as they are, so
 * an app can lay its generated part over them:
 *
 * ```
 * val source = CatalogSource(File(out, "conference"))
 * source.writeCatalog("myapp-conference")
 * source.writeContract(SessionContract)
 * source.writeDesign(Design("session-default", SessionContract.ref, "Session"), mapOf("in-app" to captured))
 * ```
 */
class CatalogSource(val dir: File) {
  /** `catalog.json`: the catalog's id and channel, and how long a client may use what it fetched. */
  fun writeCatalog(catalog: String, channel: String = "main", ttlSeconds: Long = 3600, expiresInDays: Long = 14) {
    dir.mkdirs()
    File(dir, "catalog.json").writeText(
      PRETTY.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), buildJsonObject {
        put("catalog", catalog)
        put("channel", channel)
        put("ttlSeconds", ttlSeconds)
        put("expiresInDays", expiresInDays)
      }) + "\n"
    )
  }

  /** `contracts/<fileName>.json`; the file name defaults to the last segment of its id. */
  fun writeContract(contract: Contract, fileName: String = defaultName(contract)) {
    require(NAME.matches(fileName)) { "a contract's file name is ${NAME.pattern}: '$fileName'" }
    File(dir, "contracts").mkdirs()
    File(dir, "contracts/$fileName.json").writeText(PRETTY.encodeToString(Contract.serializer(), contract) + "\n")
  }

  fun writeContract(def: ContractDef, fileName: String = defaultName(def.toContract())) = writeContract(def.toContract(), fileName)

  /**
   * `designs/<id>/design.json` and its [templates] (by template name), removing any template no
   * longer captured; returns the design's [DesignFolder].
   */
  fun writeDesign(design: Design, templates: Map<String, CapturedTemplate>): DesignFolder {
    require(NAME.matches(design.id)) { "a design id is ${NAME.pattern}: '${design.id}'" }
    val folder = DesignFolder(File(dir, "designs/${design.id}"))
    folder.dir.mkdirs()
    File(folder.dir, "design.json").writeText(PRETTY.encodeToString(Design.serializer(), design) + "\n")
    folder.write(templates)
    return folder
  }

  private companion object {
    val NAME = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    val PRETTY = Json { prettyPrint = true; encodeDefaults = false }

    fun defaultName(contract: Contract): String = contract.id.substringAfterLast('/')
  }
}
