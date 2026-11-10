package ee.schimke.livery.publisher

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** PROTOCOL.md §4.3 in the publisher: `since`, `requiresMinor`, and minor-to-minor changes. */
class ContractEvolutionPublishTest {
  private val sample = File(requireNotNull(System.getProperty("livery.samples")) { "run through Gradle: gradle/sample-catalogs.gradle.kts" }).canonicalFile

  private fun tree(): File = Files.createTempDirectory("evolution").toFile().also { sample.copyRecursively(it) }

  /** Minor 1 of the sample's tile contract: it adds an optional `subtitle` input and a `share` action. */
  private fun File.addMinor1(subtitle: String = """{ "type": "string", "since": 1 }""", share: String = """{ "kind": "app:share", "since": 1 }""") {
    val v0 = File(this, "contracts/status-tile.json").readText()
    File(this, "contracts/status-tile-1.json").writeText(
      v0.replace("\"major\": 1,", "\"major\": 1, \"minor\": 1,")
        .replace("\"available\": { \"type\": \"bool\", \"default\": true },", "\"available\": { \"type\": \"bool\", \"default\": true },\n    \"subtitle\": $subtitle,")
        .replace("\"toggle\": { \"kind\": \"app:toggle\" },", "\"toggle\": { \"kind\": \"app:toggle\" },\n    \"share\": $share,"),
    )
  }

  @Test fun templatesRequireTheMinorOfWhatTheyUse() {
    val src = tree()
    src.addMinor1()
    File(src, "designs/tile-default/launcher.json").writeText(
      """{ "target": { "surface": "launcher-widget", "rcApiLevel": 7, "profiles": ["widgets"], "size": { "minWidthDp": 110, "maxWidthDp": 400, "minHeightDp": 40, "maxHeightDp": 400 } }, "reads": ["name","subtitle"], "emits": ["toggle"] }""",
    )
    File(src, "designs/tile-default/wear-small.json").writeText(
      """{ "target": { "surface": "wear-widget", "rcApiLevel": 7, "profiles": ["glance-wear"], "container": "wear:small", "theme": "baked:dark" }, "reads": ["name"], "emits": ["share"] }""",
    )
    val m = CatalogBuilder(now = { 0 }).build(src, Layout.Static, serial = 1).manifest
    val byTarget = m.templates.filter { it.designId == "tile-default" }.associate { (it.target.container ?: it.target.surface) to it.requiresMinor }
    assertEquals(mapOf("in-app" to 0, "launcher-widget" to 1, "wear:large" to 0, "wear:small" to 1), byTarget)
  }

  @Test fun refusesAMinorThatIsNotAMinorChange() {
    val src = tree()
    // A required input in a minor breaks producers of minor 0.
    src.addMinor1(subtitle = """{ "type": "string", "required": true, "since": 1 }""")
    val problems = assertFailsWith<CatalogProblems> { CatalogBuilder().build(src, Layout.Static) }.problems
    assertTrue(problems.any { "subtitle" in it && "required" in it }, problems.toString())
    assertTrue(problems.any { "0 -> 1 is not a minor change" in it }, problems.toString())
  }

  @Test fun refusesAnAdditionWithoutSince() {
    val src = tree()
    src.addMinor1(share = """{ "kind": "app:share" }""")
    val problems = assertFailsWith<CatalogProblems> { CatalogBuilder().build(src, Layout.Static) }.problems
    assertEquals(1, problems.size, problems.toString())
    assertTrue("actions.share" in problems.single(), problems.toString())
  }

  @Test fun refusesSinceAboveTheContractsMinor() {
    val src = tree()
    src.addMinor1(subtitle = """{ "type": "string", "since": 2 }""")
    val problems = assertFailsWith<CatalogProblems> { CatalogBuilder().build(src, Layout.Static) }.problems
    assertTrue(problems.any { "since 2 is above the contract's minor 1" in it }, problems.toString())
  }

  /** The sample tile at minor 1, with its launcher template reading the new input. */
  private fun minorTree(policy: String?): File = tree().apply {
    addMinor1()
    File(this, "designs/tile-default/launcher.json").writeText(
      """{ "target": { "surface": "launcher-widget", "rcApiLevel": 7, "profiles": ["widgets"], "size": { "minWidthDp": 110, "maxWidthDp": 400, "minHeightDp": 40, "maxHeightDp": 400 } }, "reads": ["name","subtitle"], "emits": ["toggle"] }""",
    )
    if (policy != null) File(this, "catalog.json").let { it.writeText(it.readText().trimEnd().removeSuffix("}").trimEnd() + ",\n  \"minorPolicy\": $policy\n}\n") }
  }

  // The sample has assets, so its catalogs already require the resources level.
  @Test fun leniencyIsTheDefault() {
    assertEquals(RESOURCES_LEVEL, CatalogBuilder(now = { 0 }).build(minorTree(null), Layout.Static, serial = 1).manifest.minClient)
  }

  @Test fun aStrictCatalogRequiresAClientThatKnowsMinors() {
    val m = CatalogBuilder(now = { 0 }).build(minorTree("""{ "default": "strict" }"""), Layout.Static, serial = 1).manifest
    assertEquals(MINORS_LEVEL, m.minClient)
  }

  @Test fun strictnessCanBePerContract() {
    val strictTile = CatalogBuilder(now = { 0 }).build(minorTree("""{ "contracts": { "live/status-tile@1": "strict" } }"""), Layout.Static, serial = 1).manifest
    assertEquals(MINORS_LEVEL, strictTile.minClient)
    // Strict elsewhere, lenient for the tile: the tile's newer template doesn't raise the level.
    val strictBanner = CatalogBuilder(now = { 0 }).build(minorTree("""{ "default": "strict", "contracts": { "live/status-tile@1": "lenient" } }"""), Layout.Static, serial = 1).manifest
    assertEquals(RESOURCES_LEVEL, strictBanner.minClient)
  }

  @Test fun refusesAPolicyForAnUnknownContract() {
    val problems = assertFailsWith<CatalogProblems> { CatalogBuilder().build(minorTree("""{ "contracts": { "live/status-tyle@1": "strict" } }"""), Layout.Static) }.problems
    assertEquals(listOf("catalog.json minorPolicy: unknown contract live/status-tyle@1"), problems)
  }
}
