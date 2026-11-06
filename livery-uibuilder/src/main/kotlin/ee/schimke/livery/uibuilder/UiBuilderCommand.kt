package ee.schimke.livery.uibuilder

import ee.schimke.livery.publisher.CatalogProblems
import ee.schimke.livery.publisher.LiveryCommand
import ee.schimke.livery.publisher.TemplateImport
import ee.schimke.livery.protocol.Target
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `livery ui-builder …`: the UI Builder authoring flow from the command line. `scaffold` turns a
 * contract into host inputs to paste into a design; `import` exports a design (at a revision) and
 * puts it into a catalog source tree through [TemplateImport], with its provenance in the sidecar.
 */
class UiBuilderCommand(private val clientFor: (url: String, token: String) -> UiBuilderClient = ::UiBuilderClient) : LiveryCommand {
  override val name: String = "ui-builder"

  override val usage: String = """
  livery ui-builder scaffold <src> --contract <id@major> [--items <n>]
                                       prints the contract as UI Builder host inputs (stateVariables
                                       with "persistence": "host") to paste into a design
  livery ui-builder import <ui-design> <src> --as <design>/<name> --target <json>
      --api <url> [--revision <n>]         exports the design (UI_BUILDER_TOKEN in the environment) and
                                           imports it like `livery import`, with its source
"""

  override fun run(args: List<String>, env: Map<String, String>): Int {
    fun opt(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    fun fail(message: String): Int { System.err.println(message); return 64 }
    return try {
      when (args.firstOrNull()) {
        "scaffold" -> {
          val src = File(args.getOrNull(1) ?: return fail(usage))
          val contract = TemplateImport.contract(src, opt("--contract") ?: return fail(usage))
          val r = UiBuilderScaffold.hostInputs(contract, opt("--items")?.toInt())
          println(PRETTY.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), buildJsonObject { put("stateVariables", r.stateVariables) }))
          if (r.skipped.isNotEmpty()) System.err.println("not host inputs: ${r.skipped.joinToString()}")
          0
        }
        "import" -> {
          val design = args.getOrNull(1) ?: return fail(usage)
          val src = File(args.getOrNull(2) ?: return fail(usage))
          val parts = (opt("--as") ?: return fail(usage)).split('/', limit = 2)
          if (parts.size != 2) return fail(usage)
          val target = Json.decodeFromString(Target.serializer(), opt("--target") ?: return fail(usage))
          val api = opt("--api") ?: return fail(usage)
          val token = env["UI_BUILDER_TOKEN"] ?: return fail("ui-builder import needs UI_BUILDER_TOKEN in the environment")
          val e = clientFor(api, token).export(design, opt("--revision")?.toLong())
          val source = buildMap {
            put("tool", "ui-builder"); put("design", design); e.revision?.let { put("revision", it.toString()) }; put("url", e.url)
          }
          val i = TemplateImport.into(src, parts[0], parts[1], e.bytes, target, source)
          println("${i.rc} from UI Builder $design${e.revision?.let { " revision $it" } ?: ""} for ${i.contract}: reads ${i.spec.reads}, emits ${i.spec.emits}")
          0
        }
        else -> fail(usage)
      }
    } catch (e: CatalogProblems) {
      System.err.println(e.message); 2
    }
  }

  private companion object {
    val PRETTY = Json { prettyPrint = true }
  }
}
