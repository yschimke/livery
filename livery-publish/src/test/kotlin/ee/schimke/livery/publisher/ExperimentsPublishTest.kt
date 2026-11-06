package ee.schimke.livery.publisher

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExperimentsPublishTest {
  private val sample = File(System.getProperty("user.dir")).resolve("../samples/catalog").canonicalFile

  private fun tree(experiment: String): File = Files.createTempDirectory("experiments").toFile().also {
    sample.copyRecursively(it)
    File(it, "experiments").mkdirs()
    File(it, "experiments/tile-look.json").writeText(experiment)
  }

  @Test fun anExperimentShipsInTheManifest() {
    val m = CatalogBuilder(now = { 0 }).build(
      tree("""{ "id": "tile-look", "arms": [ { "id": "control" }, { "id": "retro", "designs": { "live/status-tile@1": "tile-retro" } } ] }"""),
      Layout.Static, serial = 1,
    ).manifest
    assertEquals(listOf("control", "retro"), m.experiments.single().arms.map { it.id })
  }

  @Test fun refusesArmsThatCantPlay() {
    val problems = assertFailsWith<CatalogProblems> {
      CatalogBuilder().build(
        tree("""{ "id": "tile-look", "allocationBasisPoints": 20000, "arms": [ { "id": "a", "weight": 0, "designs": { "live/status-tile@1": "nope", "promo/banner@1": "tile-retro" } }, { "id": "a", "weight": 0 } ] }"""),
        Layout.Static,
      )
    }.problems
    assertEquals(
      listOf(
        "experiment tile-look: no arm with a weight above 0",
        "experiment tile-look: allocationBasisPoints 20000 is outside 0..10000",
        "experiment tile-look: duplicate arm a",
        "experiment tile-look arm a: unknown design nope",
        "experiment tile-look arm a: design tile-retro is for live/status-tile@1, not promo/banner@1",
      ),
      problems,
    )
  }

  @Test fun refusesARuleForAnArmThatDoesntExist() {
    val src = tree("""{ "id": "tile-look", "arms": [ { "id": "control" } ] }""")
    File(src, "placements/spring-trial.json").let { it.writeText(it.readText().trimEnd().removeSuffix("}") + """, "arm": { "experiment": "tile-look", "arm": "retro" } }""") }
    val problems = assertFailsWith<CatalogProblems> { CatalogBuilder().build(src, Layout.Static) }.problems
    assertEquals(listOf("placement spring-trial: experiment tile-look has no arm retro"), problems)
  }
}
