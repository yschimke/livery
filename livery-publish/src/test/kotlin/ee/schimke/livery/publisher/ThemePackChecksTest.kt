package ee.schimke.livery.publisher

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A theme pack recolours designs: it names theme roles, in light or dark, and nothing else. */
class ThemePackChecksTest {
  private val sample = File(requireNotNull(System.getProperty("livery.samples")) { "run through Gradle: gradle/sample-catalogs.gradle.kts" }).canonicalFile

  private fun tree(vararg packs: Pair<String, String>): File = Files.createTempDirectory("themes").toFile().also { dir ->
    sample.copyRecursively(dir)
    packs.forEach { (name, json) -> File(dir, "themes/$name.json").writeText(json) }
  }

  @Test fun theSamplesPacksPass() {
    CatalogBuilder(now = { 0 }).build(tree(), Layout.Static, serial = 1)
  }

  @Test fun refusesAPackThatSetsDataOrAnUnknownMode() {
    val problems = assertFailsWith<CatalogProblems> {
      CatalogBuilder().build(
        tree(
          "loud" to """{ "id": "loud", "title": "Loud", "mode": "dim", "values": {
            "theme.primary": { "type": "color", "argb": 4291869951 },
            "title": { "type": "string", "value": "Overridden" },
            "theme.": { "type": "color", "argb": 0 } } }""",
          "dusk-copy" to """{ "id": "dusk", "title": "Dusk again", "values": {} }""",
        ),
        Layout.Static,
      )
    }.problems
    assertEquals(
      listOf(
        "duplicate theme pack dusk",
        "theme pack loud: mode 'dim' is not light or dark",
        "theme pack loud: 'title' is not a theme role (theme.<role>)",
        "theme pack loud: 'theme.' is not a theme role (theme.<role>)",
      ),
      problems,
    )
  }
}
