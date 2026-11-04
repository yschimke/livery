package ee.schimke.livery.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SelectionTest {
  private val m = Fx.manifest()
  private fun pick(q: TemplateQuery) = TemplateSelector.select(m, q)?.let { e -> m.templates.indexOf(e) }

  @Test fun picksHighestLevelThePlayerCanDraw() {
    assertEquals(1, pick(TemplateQuery("live/status-tile@1", Fx.inApp))) // v8, not v9
  }

  @Test fun chosenDesignWinsOverDefaultVariant() {
    assertEquals(5, pick(TemplateQuery("live/status-tile@1", Fx.inApp, designId = "tile-retro")))
  }

  @Test fun launcherNeedsItsSizeCovered() {
    assertEquals(3, pick(TemplateQuery("live/status-tile@1", Fx.launcher, widthDp = 200, heightDp = 100)))
    assertNull(pick(TemplateQuery("live/status-tile@1", Fx.launcher, widthDp = 50, heightDp = 100)))
  }

  @Test fun wearWidgetMatchesContainerAndBakedTheme() {
    assertEquals(4, pick(TemplateQuery("live/status-tile@1", Fx.wearSmall)))
    assertNull(pick(TemplateQuery("live/status-tile@1", Fx.wearSmall.copy(container = "wear:large"))))
  }

  @Test fun revokedDesignsAreNeverPicked() {
    val revoked = m.copy(designs = m.designs.map { if (it.id == "tile-retro") it.copy(revoked = true) else it })
    assertEquals("tile-default", TemplateSelector.select(revoked, TemplateQuery("live/status-tile@1", Fx.inApp, designId = "tile-retro"))?.designId)
  }

  @Test fun otherMajorIsNotPlayed() {
    assertNull(pick(TemplateQuery("live/status-tile@2", Fx.inApp)))
  }
}

class AutomaticSelectionTest {
  @Test fun aSkinNobodyInstalledIsNeverPickedAutomatically() {
    val m = Fx.manifest().let { it.copy(designs = it.designs.map { d -> if (d.id == "tile-retro") d.copy(variant = "default") else d }) }
    val auto = { d: ee.schimke.livery.protocol.Design -> d.install == ee.schimke.livery.protocol.InstallPolicy.Always }
    repeat(3) { assertEquals("tile-default", TemplateSelector.select(m, TemplateQuery("live/status-tile@1", Fx.launcher, 200, 100), automatic = auto)?.designId) }
  }
}
