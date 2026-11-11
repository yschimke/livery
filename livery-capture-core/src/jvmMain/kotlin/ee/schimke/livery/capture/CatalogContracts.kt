package ee.schimke.livery.capture

import ee.schimke.livery.protocol.Contract
import java.io.File
import kotlinx.serialization.json.Json

/** A catalog source tree's contracts, for the code that authors its designs. */
object CatalogContracts {
  /** The newest minor of [ref] (`id@major`) in a catalog source tree's `contracts/`. */
  fun contract(catalog: File, ref: String): Contract =
    File(catalog, "contracts").listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.name }
      .map { CONTRACTS.decodeFromString(Contract.serializer(), it.readText()) }
      .filter { it.ref == ref }.maxByOrNull { it.minor }
      ?: throw IllegalArgumentException("no contract $ref in ${File(catalog, "contracts")}")

  private val CONTRACTS = Json { ignoreUnknownKeys = true }
}
