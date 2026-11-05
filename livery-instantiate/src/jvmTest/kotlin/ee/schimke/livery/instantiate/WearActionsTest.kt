package ee.schimke.livery.instantiate

import ee.schimke.composeai.rcplayer.edit.RcDisplay
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcHostNamedAction
import ee.schimke.composeai.rcplayer.protocol.RcHostNamedActionValue
import ee.schimke.composeai.rcplayer.protocol.RcIntegerConstant
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.composeai.rcwidget.RcHostActionRewrites
import ee.schimke.composeai.rcwidget.RcWearContainerType
import ee.schimke.composeai.rcwidget.RcWearHost
import ee.schimke.livery.client.Bindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class WearActionsTest {
  private val tile = javaClass.getResource("/demo-tile.rc")!!.readBytes()
  private val document = RcDocumentCodec.decode(tile, RcWireLimits())

  private fun names(d: RcDocument): List<String> {
    val texts = d.operations.filterIsInstance<RcTextData>().associate { it.id to it.text }
    return d.operations.filterIsInstance<RcHostNamedAction>().map { texts.getValue(it.nameTextId) }
  }

  /** The value each `SendPendingIntent` action sends, by the integer constant it names. */
  private fun indices(d: RcDocument): List<Int> {
    val ints = d.operations.filterIsInstance<RcIntegerConstant>().associate { it.id to it.value }
    val texts = d.operations.filterIsInstance<RcTextData>().associate { it.id to it.text }
    return d.operations.filterIsInstance<RcHostNamedAction>()
      .filter { texts[it.nameTextId] == WearActions.SEND_PENDING_INTENT }
      .map { ints.getValue((it.value as RcHostNamedActionValue.IntegerValue).valueId) }
  }

  @Test fun eachDistinctTapGetsItsOwnPendingIntentIndex() {
    val fresh = WearActions.freshIds(document, 4)
    val (cta, a, b) = fresh
    val withTaps = document.copy(operations = document.operations + listOf(
      RcTextData(cta, "cta"), RcTextData(a, """{"route":"/a"}"""), RcTextData(b, """{"route":"/b"}"""),
      RcHostNamedAction(cta, RcHostNamedActionValue.TextValue(a)),
      RcHostNamedAction(cta, RcHostNamedActionValue.TextValue(b)),
      RcHostNamedAction(cta, RcHostNamedActionValue.TextValue(a)), // the same tap again
      RcHostNamedAction(cta, RcHostNamedActionValue.None),
    ))
    val r = WearActions.rewrite(withTaps)
    val ctaA = WidgetTap("cta", mapOf("route" to "/a"))
    val ctaB = WidgetTap("cta", mapOf("route" to "/b"))
    val bare = WidgetTap("cta", emptyMap())
    assertEquals(listOf(ctaA, ctaB, bare), r.taps.filter { it.action == "cta" })
    assertEquals(0, r.dropped)
    // Every tap now sends an index; the same tap shares one.
    assertTrue(names(r.document).all { it == WearActions.SEND_PENDING_INTENT })
    assertEquals(listOf(ctaA, ctaB, ctaA, bare), indices(r.document).takeLast(4).map { r.taps[it] })
    RcDocumentCodec.decode(RcDocumentCodec.encode(r.document), RcWireLimits()) // still a document
  }

  @Test fun aTapWithoutConstantArgumentsIsLeftAndCounted() {
    val (name, expr) = WearActions.freshIds(document, 2)
    // A text value that isn't a constant: an expression the player evaluates when tapped.
    val withTap = document.copy(operations = document.operations + listOf(
      RcTextData(name, "toggle"), RcHostNamedAction(name, RcHostNamedActionValue.TextValue(expr)),
    ))
    val before = WearActions.rewrite(document).dropped
    val r = WearActions.rewrite(withTap)
    assertEquals(before + 1, r.dropped)
    assertTrue(r.taps.none { it.action == "toggle" })
    assertTrue("toggle" in names(r.document), "left for the host to ignore")
  }

  @Test fun theTileInstantiatesForAWearContainer() = runBlocking {
    val frame = RcWearHost.Pixel.frame(RcWearContainerType.LARGE)
    val out = TemplateInstantiator { _, _, _ -> null }.instantiateForWear(tile, Bindings.EMPTY, frame, RcDisplay(2f, 1f))
    val ok = assertIs<InstantiateResult.Ok>(out)
    val d = RcDocumentCodec.decode(ok.bytes, RcWireLimits())
    // The tile's own named tap is a constant-argument tap, so it is carried.
    val tileName = RcHostActionRewrites.actionNames(document).single()
    assertEquals(listOf(tileName), ok.taps.map { it.action })
    assertEquals(listOf(0), indices(d))
    Unit
  }
}
