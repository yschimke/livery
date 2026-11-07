package ee.schimke.livery.a2ui.compose

import androidx.a2ui.compose.ui.toJsonSchemaString
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A catalog that keeps two minors of one contract major (PROTOCOL.md §4.3) offers one component, at
 * the newest minor: `promo/banner@1` minor 1 adds an optional `badge`, and the agent sees one
 * `PromoBanner` with it.
 */
class ContractMinorsA2uiTest : LiveryA2uiSurfaceTest() {
  override fun prepare(tree: File) {
    val banner = File(tree, "contracts/promo-banner.json").readText()
    File(tree, "contracts/promo-banner-1.json").writeText(
      banner.replace(""""major": 1, "minor": 0""", """"major": 1, "minor": 1""")
        .replace(""""inputs": {""", """"inputs": {
    "badge": { "type": "string", "maxLength": 12, "since": 1 },"""),
    )
  }

  @Test fun oneComponentAtTheNewestMinor() {
    val banners = catalog.components.filter { it.name == "PromoBanner" }
    assertEquals(1, banners.size, "one component per contract major")
    assertEquals(1, (banners.single() as LiveryA2uiComponent).contract.minor)
    assertTrue("\"badge\"" in catalog.toJsonSchemaString(), "the newest minor's properties")
  }
}
