package ee.schimke.livery.mcp

import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private const val USAGE = """livery-mcp — Livery's agent tools over a catalog source tree (MCP over stdio)

  livery-mcp <src> [--write] [--preview-out <dir>]
      --write              accept submit_draft: write templates into <src> after checking them
      --preview-out <dir>  accept build_preview and publish_preview: unsigned catalogs in <dir>
      --ui-builder <url>   add ui_builder_export (livery-uibuilder), with UI_BUILDER_TOKEN set
"""

/** Newline-delimited JSON-RPC on stdin and stdout, as MCP's stdio transport specifies. */
fun main(args: Array<String>) {
  val a = args.toList()
  val src = a.firstOrNull()?.let(::File)?.takeIf { File(it, "catalog.json").isFile } ?: run { System.err.println(USAGE); exitProcess(64) }
  val out = a.indexOf("--preview-out").takeIf { it >= 0 }?.let { a.getOrNull(it + 1) }?.let(::File)
  val mcp = LiveryMcp.local(src, writable = "--write" in a, previewOut = out, extensions = McpExtension.load(a, System.getenv()))
  val json = Json { ignoreUnknownKeys = true }
  val stdout = System.out.bufferedWriter()
  for (line in System.`in`.bufferedReader().lineSequence()) {
    if (line.isBlank()) continue
    val message = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
    val response = message?.let { m ->
      runCatching { mcp.handle(m) }.getOrElse { e -> internalError(m, e) }
    } ?: if (message == null) PARSE_ERROR else continue
    stdout.write(json.encodeToString(JsonElement.serializer(), response)); stdout.newLine(); stdout.flush()
  }
}

/**
 * A request the server itself failed on: an error response, never the end of the server. The
 * message names the exception (many have no message of their own), and the stack trace goes to
 * stderr, which MCP clients keep as the server's log.
 */
internal fun internalError(message: JsonObject, e: Throwable): JsonObject? {
  System.err.println("livery-mcp: ${message["method"]} failed")
  e.printStackTrace()
  val id = message["id"] ?: return null
  val text = listOfNotNull(e::class.simpleName ?: "internal error", e.message).joinToString(": ")
  return Json.parseToJsonElement(
    """{"jsonrpc":"2.0","id":$id,"error":{"code":-32603,"message":${Json.encodeToString(String.serializer(), text)}}}"""
  ) as JsonObject
}

private val PARSE_ERROR = Json.parseToJsonElement("""{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"Parse error"}}""") as JsonObject
