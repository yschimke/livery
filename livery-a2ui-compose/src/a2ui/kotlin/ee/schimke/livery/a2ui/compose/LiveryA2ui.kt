package ee.schimke.livery.a2ui.compose

import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.compose.runtime.Composable
import ee.schimke.composeai.rcplayer.compose.RcPlayerTheme
import ee.schimke.livery.client.Clock
import ee.schimke.livery.client.EventSink
import ee.schimke.livery.client.ImageResolver
import ee.schimke.livery.client.RenderPlan
import ee.schimke.livery.client.SlotPlanner
import ee.schimke.livery.client.TemplateQuery
import ee.schimke.livery.client.TemplateRepository
import ee.schimke.livery.client.TemplateSelector
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.PlayerCapabilities

/**
 * What every Livery component in an A2UI surface shares: where templates come from, the player it
 * plays them in, and the app's own handling of actions the agent didn't wire.
 *
 * @param appActions the app's handlers by action kind (`navigate`, `dismiss`, `app:…`), as for
 *   [ee.schimke.livery.client.ActionRouter]: they run for an action whose `actions` entry the agent
 *   left out.
 * @param fallback what draws when the contract has no playable template, or the agent's data
 *   doesn't bind (the agent is told either way). Nothing by default.
 */
class LiveryA2uiHost(
  val repository: TemplateRepository,
  val planner: SlotPlanner,
  val capabilities: PlayerCapabilities,
  val events: EventSink,
  val clock: Clock,
  val appActions: Map<String, suspend (plan: RenderPlan, action: String, args: Map<String, String>) -> Unit> = emptyMap(),
  val urlAllowlist: (String) -> Boolean = { false },
  val images: ImageResolver? = null,
  val theme: RcPlayerTheme = RcPlayerTheme.System,
  val fallback: @Composable (contract: Contract, reason: String) -> Unit = { _, _ -> },
)

object LiveryA2ui {
  /**
   * [base] (the basic catalog, typically) plus one [LiveryA2uiComponent] per contract in
   * [contracts]: the catalog a client advertises as [catalogId].
   */
  fun catalog(catalogId: String, base: A2uiCatalog, host: LiveryA2uiHost, contracts: List<Contract>): A2uiCatalog =
    A2uiCatalog(
      catalogId = catalogId,
      components = base.components + contracts.map { LiveryA2uiComponent(it, host) },
      functions = base.functions,
      themeSchema = base.themeSchema,
    )

  /**
   * The contracts worth advertising: those with at least one template this device can play now,
   * from what is on disk. An agent is only offered what the device can draw.
   */
  suspend fun playableContracts(repository: TemplateRepository, capabilities: PlayerCapabilities): List<Contract> {
    val manifest = repository.current() ?: return emptyList()
    return manifest.contracts.filter { TemplateSelector.select(manifest, TemplateQuery(it.ref, capabilities)) != null }
  }
}

/** The deterministic name of a contract's component (docs/A2UI.md). */
object LiveryA2uiNames {
  /** `promo/banner@1` is `PromoBanner`; `promo/banner@2` is `PromoBannerV2`. */
  fun componentName(contract: Contract): String {
    val base = contract.id.split('/', '-', '_', '.').filter { it.isNotEmpty() }.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    return if (contract.major > 1) "${base}V${contract.major}" else base
  }
}
