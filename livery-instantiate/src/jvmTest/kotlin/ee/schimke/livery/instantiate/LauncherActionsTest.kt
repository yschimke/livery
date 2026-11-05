package ee.schimke.livery.instantiate

import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcHostMetadataAction
import ee.schimke.composeai.rcplayer.protocol.RcHostNamedAction
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.composeai.rcwidget.RcHostActionRewrites
import ee.schimke.composeai.rcwidget.RcWidgetActionIds
import ee.schimke.livery.client.Bindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The tile fixture taps with one named host action carrying a text value. */
class LauncherActionsTest {
  private val tile = javaClass.getResource("/demo-tile.rc")!!.readBytes()
  private val document = RcDocumentCodec.decode(tile, RcWireLimits())

  @Test fun namedTapsBecomeNumericOnesWithTheirValueAsMetadata() {
    val name = RcHostActionRewrites.actionNames(document).single()
    val r = LauncherActions.rewrite(document)
    val id = RcWidgetActionIds().register(name)
    assertEquals(mapOf(id to name), r.actions, "the id rc-players gives that name")
    assertTrue(r.document.operations.none { it is RcHostNamedAction })
    val original = document.operations.filterIsInstance<RcHostNamedAction>().single()
    assertEquals(RcHostMetadataAction(id, original.value.valueId), r.document.operations.filterIsInstance<RcHostMetadataAction>().single())
    RcDocumentCodec.decode(RcDocumentCodec.encode(r.document), RcWireLimits()) // still a document
  }

  @Test fun instantiateForALauncherReturnsTheTapMap() = runBlocking {
    val out = assertIs<InstantiateResult.Ok>(TemplateInstantiator { _, _, _ -> null }.instantiate(tile, Bindings.EMPTY, 1, 1, numericActions = true))
    assertEquals(1, out.actions.size)
    val plain = assertIs<InstantiateResult.Ok>(TemplateInstantiator { _, _, _ -> null }.instantiate(tile, Bindings.EMPTY, 1, 1))
    assertEquals(emptyMap(), plain.actions)
    Unit
  }

  @Test fun deliveredTapsDecodeToContractActions() {
    val actions = mapOf(7 to "cta")
    assertEquals(WidgetTap("cta", mapOf("route" to "/premium")), LauncherActions.tap(actions, 7, """{"route":"/premium"}"""))
    assertEquals(WidgetTap("cta", emptyMap()), LauncherActions.tap(actions, 7, null))
    assertNull(LauncherActions.tap(actions, 8, null), "an id the template didn't declare")
    assertNull(LauncherActions.tap(actions, 7, """{"nested":{"a":1}}"""))
  }
}
