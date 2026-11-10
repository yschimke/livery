package ee.schimke.livery.publisher

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExperimentsPublishTest {
  private val sample = File(requireNotNull(System.getProperty("livery.samples")) { "run through Gradle: gradle/sample-catalogs.gradle.kts" }).canonicalFile

  private fun tree(experiment: String): File = Files.createTempDirectory("experiments").toFile().also {
    sample.copyRecursively(it)
    File(it, "experiments").mkdirs()
    File(it, "experiments/tile_look.json").writeText(experiment)
  }

  @Test fun anExperimentShipsInTheManifest() {
    val m = CatalogBuilder(now = { 0 }).build(
      tree("""{ "id": "tile_look", "arms": [ { "id": "control" }, { "id": "retro", "designs": { "live/status-tile@1": "tile-retro" } } ] }"""),
      Layout.Static, serial = 1,
    ).manifest
    assertEquals(listOf("control", "retro"), m.experiments.single().arms.map { it.id })
  }

  @Test fun refusesArmsThatCantPlay() {
    val problems = assertFailsWith<CatalogProblems> {
      CatalogBuilder().build(
        tree("""{ "id": "tile_look", "allocationBasisPoints": 20000, "arms": [ { "id": "a", "weight": 0, "designs": { "live/status-tile@1": "nope", "promo/banner@1": "tile-retro" } }, { "id": "a", "weight": 0 } ] }"""),
        Layout.Static,
      )
    }.problems
    assertEquals(
      listOf(
        "experiment tile_look: no arm with a weight above 0",
        "experiment tile_look: allocationBasisPoints 20000 is outside 0..10000",
        "experiment tile_look: duplicate arm a",
        "experiment tile_look arm a: unknown design nope",
        "experiment tile_look arm a: design tile-retro is for live/status-tile@1, not promo/banner@1",
      ),
      problems,
    )
  }

  @Test fun refusesARuleForAnArmThatDoesntExist() {
    val src = tree("""{ "id": "tile_look", "arms": [ { "id": "control" } ] }""")
    File(src, "placements/spring-trial.json").let { it.writeText(it.readText().trimEnd().removeSuffix("}") + """, "arm": { "experiment": "tile_look", "arm": "retro" } }""") }
    val problems = assertFailsWith<CatalogProblems> { CatalogBuilder().build(src, Layout.Static) }.problems
    assertEquals(listOf("placement spring-trial: experiment tile_look has no arm retro"), problems)
  }

  @Test fun aRuleForAnArmRequiresAClientThatKnowsExperiments() {
    val withDesignArm = tree("""{ "id": "tile_look", "arms": [ { "id": "control" }, { "id": "retro", "designs": { "live/status-tile@1": "tile-retro" } } ] }""")
    assertEquals(RESOURCES_LEVEL, CatalogBuilder(now = { 0 }).build(withDesignArm, Layout.Static, serial = 1).manifest.minClient, "design arms need no level")
    File(withDesignArm, "placements/spring-trial.json").let { it.writeText(it.readText().trimEnd().removeSuffix("}") + """, "arm": { "experiment": "tile_look", "arm": "retro" } }""") }
    assertEquals(EXPERIMENTS_LEVEL, CatalogBuilder(now = { 0 }).build(withDesignArm, Layout.Static, serial = 1).manifest.minClient)
  }

  @Test fun refusesIdsThatWouldShareAnalyticsKeys() {
    val problems = assertFailsWith<CatalogProblems> { CatalogBuilder().build(tree("""{ "id": "promo-copy", "arms": [ { "id": "a" } ] }"""), Layout.Static) }.problems
    assertEquals(1, problems.size, problems.toString())
    assertTrue(problems.single().startsWith("experiment promo-copy: an id is lowercase letters, digits and _"))
  }
}
