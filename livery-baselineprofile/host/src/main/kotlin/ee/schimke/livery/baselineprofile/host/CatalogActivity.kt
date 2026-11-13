package ee.schimke.livery.baselineprofile.host

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import ee.schimke.livery.client.ActionRouter
import ee.schimke.livery.client.AndroidBundled
import ee.schimke.livery.client.Clock
import ee.schimke.livery.client.FetchResult
import ee.schimke.livery.client.Fetcher
import ee.schimke.livery.client.JavaCrypto
import ee.schimke.livery.client.MemoryBlobStore
import ee.schimke.livery.client.MemoryKeyValueStore
import ee.schimke.livery.client.PlanResult
import ee.schimke.livery.client.SlotPlanner
import ee.schimke.livery.client.StaticSource
import ee.schimke.livery.client.TemplateQuery
import ee.schimke.livery.client.TemplateRepository
import ee.schimke.livery.client.TrustPolicy
import ee.schimke.livery.compose.LiveryHost
import ee.schimke.livery.compose.LiverySlot
import ee.schimke.livery.compose.rememberLiveryDisplay
import ee.schimke.livery.protocol.PlayerCapabilities
import ee.schimke.livery.protocol.Surfaces
import kotlinx.serialization.json.JsonObject

/**
 * Every design of the bundled sample catalog, each with every example its contract gives, in one
 * list of [LiverySlot]s: the path an app's feed takes, from the bundled manifest through planning
 * and binding to the player. The generator flings it end to end, so the profile covers Livery's
 * client, its Compose host and the Remote Compose player it plays templates with.
 */
class CatalogActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val clock = Clock { System.currentTimeMillis() }
    val repository = TemplateRepository(
      // Offline: the bundled catalog is the whole catalog.
      StaticSource("https://catalog.invalid/manifest.json"),
      Fetcher { _, _ -> FetchResult.Failed(503, "offline") },
      JavaCrypto,
      TrustPolicy.AllowUnsigned,
      MemoryBlobStore(),
      MemoryKeyValueStore(),
      clock,
      listOf(Capabilities),
      bundled = AndroidBundled.fromAssets(this),
    )
    val host = LiveryHost(repository, ActionRouter(emptyMap(), { false }, {}, clock), {}, clock)
    val planner = SlotPlanner(repository, {}, clock)
    setContent {
      val items by produceState(emptyList<Item>()) {
        val manifest = repository.current() ?: return@produceState
        value = manifest.designs.flatMap { design ->
          val contract = manifest.contract(design.contract) ?: return@flatMap emptyList()
          contract.examples.values.ifEmpty { listOf(JsonObject(emptyMap())) }.map { Item(design.id, design.contract, it) }
        }
      }
      // Ready the templates for this display before the list shows, as an app warms a feed.
      val display = rememberLiveryDisplay()
      LaunchedEffect(items, display) {
        host.resources.warm(items.map { TemplateQuery(it.contract, Capabilities, designId = it.designId) }.distinct(), display)
      }
      LazyColumn(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true; testTag = "catalog" }) {
        // Each design several times over: the cards after a template's first are a feed's common case.
        items(List(REPEATS) { items }.flatten().withIndex().toList(), key = { it.index }) { (index, item) ->
          LiverySlot(key = index, host = host, modifier = Modifier.fillMaxWidth(), slotId = "catalog") {
            planner.forAppData(TemplateQuery(item.contract, Capabilities, designId = item.designId), item.data)
              .takeIf { it is PlanResult.Ready } ?: PlanResult.Fallback("no ${item.designId} here")
          }
        }
      }
    }
  }

  private data class Item(val designId: String, val contract: String, val data: JsonObject)

  private companion object {
    val Capabilities = PlayerCapabilities(Surfaces.IN_APP, maxRcApiLevel = 7, profiles = setOf("androidx"))
    const val REPEATS = 4
  }
}
