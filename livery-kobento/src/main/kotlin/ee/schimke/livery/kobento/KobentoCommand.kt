package ee.schimke.livery.kobento

import ee.schimke.livery.protocol.Target
import ee.schimke.livery.publisher.CatalogProblems
import ee.schimke.livery.publisher.LiveryCommand
import ee.schimke.livery.publisher.TemplateImport
import ee.schimke.livery.publisher.contract
import ee.schimke.livery.publisher.into
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okio.ByteString.Companion.toByteString

/**
 * `livery kobento …`: Kobento documents into a catalog source tree. `scaffold` writes a contract as
 * the start of a Kobento document; `check` holds a document to a target; `import` compiles it (plain
 * authoring JSON here, a `.rc` as it is), holds it to the target and puts it into the tree through
 * [TemplateImport], which checks it against its design's contract, with its provenance in the sidecar.
 */
class KobentoCommand : LiveryCommand {
  override val name: String = "kobento"

  override val usage: String = """
  livery kobento scaffold <src> --contract <id@major> [--items <n>]
                                       prints the contract as Kobento / AndroidX authoring JSON: its
                                       values as USER: named values, ready to lay out
  livery kobento check <doc.json|doc.rc> --target <json>
                                       what in the document the target's player wouldn't play
  livery kobento import <doc.json|doc.rc> <src> --as <design>/<name> --target <json>
      [--source <key>=<value>]...          compiles the document and imports it like `livery import`
                                           (a document using Kobento components: `convert` it with
                                           Kobento's MCP first and import the .rc)
"""

  override fun run(args: List<String>, env: Map<String, String>): Int {
    fun opt(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    fun fail(message: String): Int { System.err.println(message); return 64 }
    return try {
      when (args.firstOrNull()) {
        "scaffold" -> {
          val src = File(args.getOrNull(1) ?: return fail(usage))
          val contract = TemplateImport.contract(src, opt("--contract") ?: return fail(usage))
          val r = KobentoScaffold.authoringJson(contract, opt("--items")?.toInt())
          println(PRETTY.encodeToString(JsonObject.serializer(), r.json))
          r.notes.forEach { System.err.println(it) }
          0
        }
        "check" -> {
          val doc = File(args.getOrNull(1) ?: return fail(usage))
          val target = Json.decodeFromString(Target.serializer(), opt("--target") ?: return fail(usage))
          val problems = KobentoTargetCheck.problems(KobentoDocument.compile(doc.readBytes()), target)
          problems.forEach { System.err.println("${doc.name}: $it") }
          if (problems.isEmpty()) { println("${doc.name}: plays on $target"); 0 } else 2
        }
        "import" -> {
          val doc = File(args.getOrNull(1) ?: return fail(usage))
          val src = File(args.getOrNull(2) ?: return fail(usage))
          val parts = (opt("--as") ?: return fail(usage)).split('/', limit = 2)
          if (parts.size != 2) return fail(usage)
          val target = Json.decodeFromString(Target.serializer(), opt("--target") ?: return fail(usage))
          val raw = doc.readBytes()
          val kind = KobentoDocument.kindOf(raw)
          val bytes = KobentoDocument.compile(raw)
          val problems = KobentoTargetCheck.problems(bytes, target)
          if (problems.isNotEmpty()) throw CatalogProblems(problems.map { "${parts[0]}/${parts[1]}: $it" })
          val extra = args.indices.filter { args[it] == "--source" }.mapNotNull { args.getOrNull(it + 1) }.map {
            val kv = it.split('=', limit = 2)
            if (kv.size != 2 || kv[0].isEmpty()) return fail("--source takes <key>=<value>: '$it'")
            kv[0] to kv[1]
          }
          val source = linkedMapOf("tool" to "kobento", "file" to doc.name, "format" to if (kind == KobentoDocument.Kind.Rc) "rc" else "json") + extra
          val i = TemplateImport.into(src, parts[0], parts[1], bytes.toByteString(), target, source)
          println("${i.rc} from Kobento ${doc.name} for ${i.contract}: reads ${i.spec.reads}, emits ${i.spec.emits}")
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
