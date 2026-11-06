package ee.schimke.livery.publisher

import ee.schimke.livery.protocol.Presentation
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** PROTOCOL.md §8.2 in the publisher: screen templates, `present` actions, screen rules. */
class ScreensPublishTest {
  private val sample = File(System.getProperty("user.dir")).resolve("../samples/catalog").canonicalFile

  /**
   * The sample, with a details contract shown as a screen: a full-screen design, the banner's
   * `details` action opening it, and the banner's rule shipping that screen.
   */
  private fun tree(
    detailsActions: String = """"close": { "kind": "dismiss" }""",
    present: String = """{ "kind": "present", "placement": "promo.details" }""",
    ruleExtra: String = """, "presentation": "inline", "screens": { "details": { "template": { "designId": "details-full" }, "data": { "title": "Half price" }, "presentation": "fullscreen" } }""",
  ): File = Files.createTempDirectory("screens").toFile().also { src ->
    sample.copyRecursively(src)
    File(src, "contracts/promo-details.json").writeText(
      """{ "id": "promo/details", "major": 1, "inputs": { "title": { "type": "string", "required": true } }, "actions": { $detailsActions } }""",
    )
    File(src, "contracts/promo-banner.json").let { it.writeText(it.readText().replace(""""dismiss": { "kind": "dismiss" }""", """"dismiss": { "kind": "dismiss" }, "details": $present""")) }
    File(src, "designs/details-full").mkdirs()
    File(src, "designs/details-full/design.json").writeText("""{ "id": "details-full", "contract": "promo/details@1", "title": "Details", "install": "Always" }""")
    File(src, "designs/details-full/full.rc").writeBytes(byteArrayOf(1, 2, 3))
    File(src, "designs/details-full/full.json").writeText(
      """{ "target": { "surface": "in-app", "rcApiLevel": 7, "container": "${Presentation.Fullscreen.container}" }, "reads": ["title", "livery.inset.top"], "emits": ["close"] }""",
    )
    File(src, "placements/spring-trial.json").let { it.writeText(it.readText().trimEnd().removeSuffix("}") + ruleExtra + "}") }
  }

  @Test fun screensShipAndRequireLevelFive() {
    val m = CatalogBuilder(now = { 0 }).build(tree(), Layout.Static, serial = 1).manifest
    assertEquals(PRESENTATION_LEVEL, m.minClient)
    val rule = m.placements.single()
    assertEquals(Presentation.Fullscreen, rule.screens.getValue("details").presentation)
    assertEquals("screen:fullscreen", m.templates.single { it.designId == "details-full" }.target.container)
  }

  @Test fun aScreenTemplateMustBeClosable() {
    val problems = assertFailsWith<CatalogProblems> {
      CatalogBuilder().build(tree(detailsActions = """"more": { "kind": "navigate" }""").also {
        File(it, "designs/details-full/full.json").writeText(File(it, "designs/details-full/full.json").readText().replace("\"close\"", "\"more\""))
      }, Layout.Static)
    }.problems
    assertEquals(listOf("details-full/full.rc: a screen:fullscreen template's contract promo/details@1 declares no dismiss action"), problems)
  }

  @Test fun presentNamesAPlacementAndScreensAreChecked() {
    val noPlacement = assertFailsWith<CatalogProblems> { CatalogBuilder().build(tree(present = """{ "kind": "present" }"""), Layout.Static) }.problems
    assertTrue(noPlacement.any { "action details is `present` but names no placement" in it }, noPlacement.toString())
    val badScreen = assertFailsWith<CatalogProblems> {
      CatalogBuilder().build(tree(ruleExtra = """, "screens": { "cta": { "template": { "designId": "nope" }, "data": {} } }"""), Layout.Static)
    }.problems
    assertTrue(badScreen.any { "screen cta isn't a `present` action" in it }, badScreen.toString())
    assertTrue(badScreen.any { "screen cta: unknown design nope" in it }, badScreen.toString())
  }
}
