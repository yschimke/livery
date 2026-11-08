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
import ee.schimke.livery.instantiate.DocumentAdmission
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
  /**
   * Documents the agent sends with the message (`template: {inline, signature}`): who may sign
   * them, the risky features a signed one may use, and whether unsigned ones play at all. Null
   * refuses every one: the component then draws only reviewed designs from the catalog.
   */
  val documents: DocumentAdmission? = null,
  val documentFetcher: ee.schimke.livery.client.RedirectingFetcher? = null,
  val decider: ee.schimke.livery.client.Decider? = null,
  /** App-owned context; never taken from the agent's query params. */
  val decisionContext: (() -> ee.schimke.livery.client.DecisionContext)? = null,
) {
  internal val namedDocuments = ee.schimke.livery.instantiate.NamedDocuments(repository, documents, documentFetcher)

}

object LiveryA2uiCompose {
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
   * The contracts worth advertising: those the repository can resolve a template for now, from
   * disk alone (blobs and assets downloaded, designs installed or shipped by default), as a slot
   * would. An agent is only offered what the device can draw.
   */
  suspend fun playableContracts(repository: TemplateRepository, capabilities: PlayerCapabilities): List<Contract> {
    val manifest = repository.current() ?: return emptyList()
    // One component per contract major, at the newest minor the catalog keeps (as Manifest.contract).
    return ee.schimke.livery.a2ui.LiveryA2ui.newestMinors(manifest.contracts)
      .filter { repository.resolve(TemplateQuery(it.ref, capabilities)) != null }
  }
}

