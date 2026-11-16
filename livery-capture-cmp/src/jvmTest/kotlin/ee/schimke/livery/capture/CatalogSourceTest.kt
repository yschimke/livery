package ee.schimke.livery.capture

import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.ContractDef
import ee.schimke.livery.protocol.Design
import ee.schimke.livery.protocol.InstallPolicy
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

private object NoteContract : ContractDef("demo/note", major = 1, title = "Note") {
  val text = string("text", required = true, maxLength = 80)
}

/** A catalog source tree's generated part, written from Kotlin over its hand-edited part. */
class CatalogSourceTest {
  private val dir: File = Files.createTempDirectory("source").toFile()
  private val json = Json { ignoreUnknownKeys = true }

  @Test fun writesTheGeneratedPartOfATreeAndLeavesTheRest() {
    File(dir, "themes").mkdirs()
    File(dir, "themes/dusk.json").writeText("{}")
    val source = CatalogSource(dir)
    source.writeCatalog("demo", ttlSeconds = 60)
    source.writeContract(NoteContract)
    val folder = source.writeDesign(Design("note-default", NoteContract.ref, "Note", install = InstallPolicy.Always, tags = mapOf("series" to "a")), emptyMap())

    assertEquals("""{"catalog":"demo","channel":"main","ttlSeconds":60,"expiresInDays":14}""", Json.parseToJsonElement(File(dir, "catalog.json").readText()).toString())
    assertEquals(NoteContract.toContract(), json.decodeFromString(Contract.serializer(), File(dir, "contracts/note.json").readText()))
    val design = json.decodeFromString(Design.serializer(), File(folder.dir, "design.json").readText())
    assertEquals(InstallPolicy.Always, design.install)
    assertEquals(mapOf("series" to "a"), design.tags)
    assertEquals(File(dir, "designs/note-default"), folder.dir)
    assertTrue(File(dir, "themes/dusk.json").isFile, "hand-edited files stay")
    assertEquals(listOf("design.json"), folder.dir.list()!!.toList(), "DesignFolder keeps design.json")
  }
}
