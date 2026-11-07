package ee.schimke.livery.publisher

import ee.schimke.livery.client.Sha256
import ee.schimke.livery.instantiate.TemplateInspector
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.Design
import ee.schimke.livery.protocol.InstallPolicy
import ee.schimke.livery.protocol.Target
import kotlinx.serialization.json.Json

/**
 * One document published on its own, for a look before it joins any catalog: a draft from an
 * agent, an export from an authoring tool, an `.rc` someone sent. It becomes a catalog of one
 * contract and one `Always` design, so any Livery client (a debug build allowing unsigned
 * catalogs) plays it with no new code, on a channel named after its hash.
 *
 * It goes through the same gate as a whole catalog: the document is inspected against the contract
 * (it reads and emits only what the contract declares), and the contract's examples bind.
 */
object PreviewCatalog {
  data class Result(val output: CatalogBuilder.Output, val channel: String)

  /**
   * @param target the host it is for; by default any in-app player of level 7 or more.
   * @param source where the document came from (tool, design, revision), kept on its entry.
   */
  fun build(
    contract: Contract,
    document: ByteArray,
    target: Target = Target(surface = "in-app", rcApiLevel = 7),
    title: String? = null,
    source: Map<String, String> = emptyMap(),
    expiresInDays: Long = 7,
  ): Result {
    val facts = TemplateInspector.facts(document) ?: throw CatalogProblems(listOf("not a Remote Compose document"))
    val problems = buildList {
      val reads = facts.declaredReads.filterNot { CatalogBuilder.declares(contract, it) }
      if (reads.isNotEmpty()) add("reads values outside ${contract.ref}: ${reads.sorted()}")
      val emits = facts.emits.filterNot { it in contract.actions }
      if (emits.isNotEmpty()) add("emits actions ${contract.ref} doesn't declare: ${emits.sorted()}")
    }
    if (problems.isNotEmpty()) throw CatalogProblems(problems)
    val sha = Sha256.hex(document)
    val channel = "preview-${sha.take(12)}"
    val designId = "preview-${sha.take(12)}"
    val json = Json { prettyPrint = true; encodeDefaults = false }
    val src = MemorySource()
    src.resolve("catalog.json").writeText(json.encodeToString(CatalogConfig.serializer(), CatalogConfig("preview", channel, ttlSeconds = 60, expiresInDays = expiresInDays)))
    src.resolve("contracts").resolve("contract.json").writeText(json.encodeToString(Contract.serializer(), contract))
    val dir = src.resolve("designs").resolve(designId)
    dir.resolve("design.json").writeText(json.encodeToString(Design.serializer(),
      Design(designId, contract.ref, title ?: "Preview ${sha.take(12)}", install = InstallPolicy.Always)))
    dir.resolve("preview.rc").writeBytes(document)
    dir.resolve("preview.json").writeText(json.encodeToString(TemplateSpec.serializer(),
      TemplateSpec(target, reads = facts.declaredReads.sorted(), emits = facts.emits.sorted(), source = source)))
    return Result(CatalogBuilder().build(src, Layout.Static), channel)
  }
}
