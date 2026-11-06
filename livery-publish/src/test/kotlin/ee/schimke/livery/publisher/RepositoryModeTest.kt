package ee.schimke.livery.publisher

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Repository mode: seasons in the source tree, and what `livery check` tells a reviewer. */
class RepositoryModeTest {
  private val sample = File(System.getProperty("user.dir")).resolve("../samples/catalog").canonicalFile

  private fun tree(): File = Files.createTempDirectory("repo-mode").toFile().also { sample.copyRecursively(it) }

  private fun File.write(path: String, text: String) = File(this, path).apply { parentFile.mkdirs() }.writeText(text)

  /** tile-retro shipped to everyone but drawn only by a season. */
  private fun File.retroSeasonal() = write("designs/tile-retro/design.json", File(this, "designs/tile-retro/design.json").readText().replace("\"OnRequest\"", "\"Always\", \"automatic\": false"))

  /** tile-retro shipped to everyone and automatic, as an experiment arm's design may be. */
  private fun File.retroAlways() = write("designs/tile-retro/design.json", File(this, "designs/tile-retro/design.json").readText().replace("OnRequest", "Always"))

  @Test fun aSeasonShipsAsAFullAllocationExperimentAheadOfTheOthers() {
    val src = tree().apply {
      retroSeasonal()
      write("experiments/tile_look.json", """{ "id": "tile_look", "arms": [ { "id": "control" }, { "id": "retro", "designs": { "live/status-tile@1": "tile-retro" } } ] }""")
      write("seasons/winter.json", """{ "id": "winter_2026", "start": "2026-12-01", "end": "2027-01-07T00:00:00+13:00", "designs": { "live/status-tile@1": "tile-retro" } }""")
    }
    val m = CatalogBuilder(now = { 0 }).build(src, Layout.Static, serial = 1).manifest
    assertEquals(listOf("winter_2026", "tile_look"), m.experiments.map { it.id }, "a season comes first")
    val winter = m.experiments.first()
    assertEquals(10_000, winter.allocationBasisPoints)
    assertEquals(listOf(Seasons.ARM), winter.arms.map { it.id })
    assertEquals(1_796_083_200_000, winter.startEpochMs, "midnight UTC")
    assertEquals(1_799_233_200_000, winter.endEpochMs, "the offset is honoured")
    assertEquals(AUTOMATIC_LEVEL, m.minClient, "an older client would draw the season's design all year")
  }

  @Test fun outsideItsSeasonTheUsualDesignDraws() {
    val src = tree().apply {
      retroSeasonal()
      write("seasons/winter.json", """{ "id": "winter_2026", "start": "2026-12-01", "end": "2027-01-07", "designs": { "live/status-tile@1": "tile-retro" } }""")
    }
    val m = CatalogBuilder(now = { 0 }).build(src, Layout.Static, serial = 1).manifest
    // Plain automatic selection, as a client draws when no season or experiment is running: the
    // season's design (its own variant) never wins over the usual default design.
    val inApp = ee.schimke.livery.protocol.PlayerCapabilities("in-app", 7, setOf("androidx"))
    fun pick(caps: ee.schimke.livery.protocol.PlayerCapabilities, variant: String? = null) =
      ee.schimke.livery.client.TemplateSelector.select(m, ee.schimke.livery.client.TemplateQuery("live/status-tile@1", caps, variant = variant)) { true }?.designId
    assertEquals("tile-default", pick(inApp))
    assertEquals("tile-default", pick(inApp, variant = "retro"), "not even when its variant is asked for")
    // A budget only the season's (small) template fits: nothing draws, never the season's design.
    val retro = m.templates.first { it.designId == "tile-retro" }
    assertEquals(null, pick(inApp.copy(maxBytes = retro.bytes)))
    // The season's arm names it, and then it draws.
    assertEquals("tile-retro", ee.schimke.livery.client.TemplateSelector.select(m, ee.schimke.livery.client.TemplateQuery("live/status-tile@1", inApp, designId = "tile-retro")) { true }?.designId)
  }

  @Test fun aSeasonsDesignMustNotBeAutomatic() {
    val src = tree().apply {
      retroAlways()
      write("seasons/winter.json", """{ "id": "winter_2026", "start": "2026-12-01", "end": "2027-01-07", "designs": { "live/status-tile@1": "tile-retro" } }""")
    }
    val problems = assertFailsWith<CatalogProblems> { CatalogBuilder().build(src, Layout.Static) }.problems
    assertEquals(listOf("season winter_2026: design tile-retro is automatic, so it would show outside the season too; set \"automatic\": false on it"), problems)
  }

  @Test fun seasonsAreChecked() {
    val src = tree().apply {
      write("seasons/a.json", """{ "id": "a_season", "start": "2026-12-01", "end": "2026-11-01", "designs": { "live/status-tile@1": "tile-retro" } }""")
      write("seasons/b.json", """{ "id": "b_season", "start": "2026-12-01", "end": "2027-01-01", "designs": { "live/status-tile@1": "tile-retro" } }""")
      write("seasons/c.json", """{ "id": "c_season", "start": "2026-12-15", "end": "2027-02-01", "designs": { "live/status-tile@1": "tile-default", "promo/banner@1": "nope" } }""")
      write("seasons/d.json", """{ "id": "d_season", "start": "soon", "end": "2027-02-01", "designs": {} }""")
    }
    val problems = assertFailsWith<CatalogProblems> { CatalogBuilder().build(src, Layout.Static) }.problems
    assertTrue(problems.any { "a_season: ends (2026-11-01) before it starts" in it }, problems.toString())
    assertTrue(problems.any { "b_season: design tile-retro is OnRequest; a season's design must be Always" in it }, problems.toString())
    assertTrue(problems.any { "c_season: unknown design nope" in it }, problems.toString())
    assertTrue(problems.any { "c_season: design tile-default is automatic" in it }, problems.toString())
    assertTrue(problems.any { "seasons b_season and c_season overlap for [live/status-tile@1]" in it }, problems.toString())
    assertTrue(problems.any { "d_season: start 'soon' is neither a date" in it }, problems.toString())
    assertTrue(problems.any { "d_season: names no design" in it }, problems.toString())
  }

  @Test fun theReviewReportShowsExperimentSeasonAndRuleChanges() {
    val before = tree().apply {
      retroSeasonal()
      write("experiments/tile_look.json", """{ "id": "tile_look", "allocationBasisPoints": 5000, "arms": [ { "id": "control" }, { "id": "retro", "designs": { "live/status-tile@1": "tile-retro" } } ] }""")
    }
    val after = Files.createTempDirectory("after").toFile().also { before.copyRecursively(it) }.apply {
      // The test moves to 20% with a re-weighted arm, a season starts and the banner rule ends earlier.
      write("experiments/tile_look.json", """{ "id": "tile_look", "allocationBasisPoints": 2000, "arms": [ { "id": "control" }, { "id": "retro", "weight": 3, "designs": { "live/status-tile@1": "tile-retro" } } ] }""")
      write("seasons/winter.json", """{ "id": "winter_2026", "start": "2026-12-01", "end": "2027-01-07", "designs": { "live/status-tile@1": "tile-retro" } }""")
      write("placements/spring-trial.json", File(this, "placements/spring-trial.json").readText().replace("1800000000000", "1795000000000"))
    }
    after.write("designs/banner-bold/design.json", File(after, "designs/banner-bold/design.json").readText().replace("\"title\": \"", "\"revoked\": true, \"title\": \""))
    val r = CompatibilityCheck.compare(after, before, "main")
    assertEquals(listOf("banner-bold"), r.designs.map { it.id })
    assertTrue(r.designs.single().changes.single().startsWith("revoked: false -> true"), r.designs.toString())
    assertTrue(r.ok, r.problems.toString())
    assertEquals(listOf("tile_look" to "changed", "winter_2026" to "added"), r.experiments.map { it.id to it.status })
    assertEquals("season", r.experiments.single { it.id == "winter_2026" }.kind)
    assertTrue("runs 2026-12-01T00:00:00Z to 2027-01-07T00:00:00Z" in r.experiments.single { it.id == "winter_2026" }.changes)
    assertEquals(listOf("endEpochMs: 1800000000000 -> 1795000000000"), r.placements.single().changes)
    assertTrue(r.warnings.any { "tile_look: allocation lowered (5000 -> 2000" in it }, r.warnings.toString())
    assertTrue(r.warnings.any { "tile_look: arms or weights changed" in it }, r.warnings.toString())

    val md = CompatibilityCheck.markdown(r)
    assertTrue(md.startsWith("### Livery check against `main`: compatible"), md)
    assertTrue("- added season `winter_2026`" in md, md)
    assertTrue("**For review**" in md, md)
  }
}
