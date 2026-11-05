package ee.schimke.livery.mcp

import kotlinx.serialization.json.JsonObject

/**
 * Tools an optional module adds to the server, such as an authoring tool's import
 * (`livery-uibuilder`). Livery's own tools stay tool-agnostic: an extension only produces
 * documents (with their `source`), which then go through the same gate as any other:
 * `validate_template`, `render_preview`, `submit_draft`, `publish_preview`.
 *
 * Found with [java.util.ServiceLoader] ([Factory]); a factory returns null when its tool isn't
 * configured, so a server without it offers nothing extra.
 */
interface McpExtension {
  /** The tools' definitions, as `tools/list` returns them. */
  val tools: List<JsonObject>

  /** A call to one of [tools], as MCP content; [server] reaches Livery's own tools. */
  fun call(name: String, args: JsonObject, server: LiveryMcp): JsonObject

  fun interface Factory {
    /** The extension for these command-line [args] and [env], or null when it isn't configured. */
    fun create(args: List<String>, env: Map<String, String>): McpExtension?
  }

  companion object {
    fun load(args: List<String>, env: Map<String, String>): List<McpExtension> =
      java.util.ServiceLoader.load(Factory::class.java).mapNotNull { it.create(args, env) }
  }
}
