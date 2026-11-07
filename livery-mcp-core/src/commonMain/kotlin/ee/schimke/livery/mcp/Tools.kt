package ee.schimke.livery.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The tools' names, descriptions and input schemas, as `tools/list` returns them. */
internal object Tools {
  private class Arg(val name: String, val type: String, val description: String, val required: Boolean = false)

  private fun tool(name: String, description: String, vararg args: Arg) = buildJsonObject {
    put("name", name)
    put("description", description)
    putJsonObject("inputSchema") {
      put("type", "object")
      putJsonObject("properties") {
        for (a in args) putJsonObject(a.name) { put("type", a.type); put("description", a.description) }
      }
      putJsonArray("required") { args.filter { it.required }.forEach { add(it.name) } }
      put("additionalProperties", false)
    }
  }

  private val contract = Arg("contract", "string", "A contract reference, id@major (e.g. promo/banner@1)", required = true)

  private val read: List<JsonObject> = listOf(
    tool("list_contracts", "The catalog's contracts: what each lets a template read and emit, and its examples."),
    tool("get_contract", "One contract in full: inputs with types and limits, lists, actions with kinds and args, examples.", contract),
    tool("list_designs", "The catalog's designs, with each template's target, size and declared reads and emits.",
      Arg("contract", "string", "Only designs for this contract (id@major)")),
    tool("validate_data", "Binds data to a contract the way every client does (ContractBinder): the values it would play, or every problem. Use it for copy, campaigns and decisions.",
      contract, Arg("data", "object", "The data, as an app, decision or feed item would send it", required = true)),
    tool("validate_template", "Reads a Remote Compose document itself: the named values it reads and actions it emits, checked against a contract. Returns the sidecar reads/emits to submit with it.",
      contract, Arg("rc_base64", "string", "The .rc document, base64", required = true),
      Arg("max_bytes", "integer", "The host's byte budget (a launcher widget's is about 50000)")),
    tool("render_preview", "Renders a template with data to a PNG, as a person would see it. Name a design and template from the catalog, or pass rc_base64 with a contract. Data defaults to the contract's first example.",
      Arg("design", "string", "A design id in the catalog"), Arg("template", "string", "The template's name in that design (its .rc file name)"),
      Arg("rc_base64", "string", "A document not in the catalog yet, base64"), Arg("contract", "string", "With rc_base64: the contract to bind data to"),
      Arg("example", "string", "One of the contract's examples"), Arg("data", "object", "Data to render with, instead of an example"),
      Arg("width_dp", "integer", "Width in dp, 1 to 4096 (default 360)"), Arg("height_dp", "integer", "Height in dp, 1 to 4096 (default 200)"),
      Arg("density", "integer", "Pixels per dp, 1 to 4 (default 2)"), Arg("dark", "boolean", "Dark theme"),
      Arg("revision", "string", "With design and template: render the template as it was at this git commit (from list_history)")),
    tool("check_compatibility", "Compares the catalog with an earlier version of itself, as `livery check` does: contract changes marked compatible or breaking, and for each template what it reads and emits now, the contract minor it requires, its size, and which values only its own default covers. ok=false means apps already installed would break.",
      Arg("against", "string", "A git revision of the catalog's repository (default HEAD, so: what the working tree changes), e.g. origin/main, or another source tree's path"),
      Arg("inspect", "boolean", "Also decode every document: its reads, emits and types against the contract (default false)")),
    tool("a2ui_component_schema", "The catalog's contracts as A2UI components (docs/A2UI.md): one component's JSON schema, or the whole A2UI catalog to prompt an agent with. A component takes the contract's inputs as A2UI values or {\"path\": …} bindings, plus design, variant, actions (an A2UI action per contract action) and template.",
      Arg("contract", "string", "One contract (id@major); all of them when left out"),
      Arg("catalog_id", "string", "The A2UI catalog id the client advertises (default livery)")),
    tool("validate_a2ui", "Checks an A2UI message (or an array of messages) before it is sent: every Livery component's properties, its values through the same contract gate every client runs (literals, and bindings when data_model is given), its actions, template and design. Basic-catalog components are A2UI's to check.",
      Arg("message", "object", "An A2UI message such as {\"version\": \"v0.9\", \"updateComponents\": …}, or an array of them", required = true),
      Arg("data_model", "object", "The surface's data model, to check bound values too")),
    tool("list_history", "A design's version history from the catalog's git log: for each template, the commits that changed it and where it came from (its sidecar's source). Render one with render_preview's revision.",
      Arg("design", "string", "A design id in the catalog", required = true), Arg("template", "string", "Only this template"),
      Arg("limit", "integer", "At most this many commits per template (default 20)")),
  )

  private val write = tool("submit_draft",
    "Writes a template into the catalog source tree after reading the document against its sidecar and checking the whole catalog as `livery build` does. A draft that fails is not written. Drafts are unsigned and reviewed by a person.",
    Arg("design", "string", "The design id (its directory)", required = true),
    Arg("template", "string", "The template's name, e.g. in-app or launcher", required = true),
    Arg("rc_base64", "string", "The .rc document, base64", required = true),
    Arg("sidecar", "object", "The template's sidecar: target, tags, reads, emits, fonts", required = true),
    Arg("design_json", "object", "For a new design: its design.json (id, contract, title, install)"))

  private val preview = tool("build_preview",
    "Builds the catalog, unsigned, for a preview channel (never the catalog's own), for a reviewer's debug build to play.",
    Arg("channel", "string", "A preview channel name, e.g. pr-12 or draft-banner", required = true))

  private val publishPreview = tool("publish_preview",
    "Publishes one document on its own, unsigned, as a catalog of one design for a debug build to play: a quick look before it joins the catalog. It is checked against the contract first.",
    contract, Arg("rc_base64", "string", "The .rc document, base64", required = true),
    Arg("target", "object", "The host it is for (default: in-app, rcApiLevel 7)"), Arg("title", "string", "A title for the preview design"),
    Arg("source", "object", "Where it came from: tool, design, revision"))

  fun list(writable: Boolean, preview: Boolean): JsonArray = buildJsonArray {
    read.forEach { add(it) }
    if (writable) add(write)
    if (preview) { add(this@Tools.preview); add(publishPreview) }
  }
}
