package ee.schimke.livery.uibuilder

import ee.schimke.livery.mcp.LiveryMcp
import ee.schimke.livery.mcp.McpExtension
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `ui_builder_export`: a UI Builder design, at a revision or its latest, as a document with its
 * provenance (`source`: tool, design, revision, url). The agent then uses Livery's own tools on it:
 * `validate_template` for its sidecar, `render_preview`, then `submit_draft` with that `source` in
 * the sidecar, or `publish_preview` for a quick look. Re-exporting another revision is how a person
 * or agent moves a template along UI Builder's history.
 */
class UiBuilderExtension(private val client: UiBuilderClient) : McpExtension {
  override val tools: List<JsonObject> = listOf(buildJsonObject {
    put("name", TOOL)
    put("description", "Exports a Compose UI Builder design (at a revision, or its latest) as a Remote Compose document, " +
      "with its source to keep in the sidecar. Then validate_template, render_preview, and submit_draft or publish_preview it.")
    putJsonObject("inputSchema") {
      put("type", "object")
      putJsonObject("properties") {
        putJsonObject("ui_design") { put("type", "string"); put("description", "The UI Builder design id") }
        putJsonObject("revision") { put("type", "integer"); put("description", "A UI Builder revision; default its latest") }
      }
      putJsonArray("required") { add("ui_design") }
      put("additionalProperties", false)
    }
  })

  override fun call(name: String, args: JsonObject, server: LiveryMcp): JsonObject = try {
    val design = (args["ui_design"] as? JsonPrimitive)?.contentOrNull ?: error("missing argument 'ui_design'")
    val e = client.export(design, (args["revision"] as? JsonPrimitive)?.longOrNull)
    buildJsonObject {
      putJsonArray("content") {
        add(buildJsonObject {
          put("type", "text")
          put("text", buildJsonObject {
            put("rc_base64", Base64.getEncoder().encodeToString(e.bytes)); put("bytes", e.bytes.size)
            putJsonObject("source") {
              put("tool", "ui-builder"); put("design", design); e.revision?.let { put("revision", it.toString()) }; put("url", e.url)
            }
            put("next", "validate_template with this rc_base64, then render_preview; put source in the sidecar of submit_draft")
          }.toString())
        })
      }
    }
  } catch (e: Exception) {
    buildJsonObject {
      putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", e.message ?: "export failed") }) }
      put("isError", true)
    }
  }

  /** On with `--ui-builder <api url>` and `UI_BUILDER_TOKEN` in the environment. */
  class Factory : McpExtension.Factory {
    override fun create(args: List<String>, env: Map<String, String>): McpExtension? {
      val url = args.indexOf("--ui-builder").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) } ?: return null
      require(url.startsWith("https://") || url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1")) {
        "--ui-builder must be an https URL (or a local one)"
      }
      val token = env["UI_BUILDER_TOKEN"] ?: error("--ui-builder needs UI_BUILDER_TOKEN in the environment")
      return UiBuilderExtension(UiBuilderClient(url, token))
    }
  }

  companion object {
    const val TOOL: String = "ui_builder_export"
  }
}
