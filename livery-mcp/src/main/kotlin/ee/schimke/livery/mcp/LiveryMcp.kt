package ee.schimke.livery.mcp

import ee.schimke.livery.publisher.build
import ee.schimke.livery.publisher.check
import ee.schimke.livery.publisher.write
import ee.schimke.livery.client.BindResult
import ee.schimke.livery.client.ContractBinder
import ee.schimke.livery.client.ContractEvolution
import ee.schimke.livery.instantiate.TemplateInspector
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.Design
import ee.schimke.livery.protocol.Manifest
import ee.schimke.livery.protocol.NamedValue
import ee.schimke.livery.publisher.CatalogBuilder
import ee.schimke.livery.publisher.CatalogConfig
import ee.schimke.livery.publisher.CatalogProblems
import ee.schimke.livery.publisher.CompatibilityCheck
import ee.schimke.livery.publisher.Layout
import ee.schimke.livery.publisher.PreviewCatalog
import ee.schimke.livery.publisher.TemplateSpec
import ee.schimke.livery.render.RenderSpec
import ee.schimke.livery.render.TemplateRenderer
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Livery's agent tools (DESIGN.md "AI workflows") over one catalog source tree, as a Model Context
 * Protocol server: the contract, the gate and a render, for any agent and any authoring tool.
 *
 * Read tools always work. [writable] lets `submit_draft` write a template into the tree (the
 * repository + CI workflow: the agent works in a branch and CI publishes it); [previewOut] lets
 * `build_preview` write an unsigned catalog for a preview channel. Nothing here signs: production
 * catalogs are signed by a key the agent doesn't hold.
 *
 * Transport-free: [handle] takes one JSON-RPC message and returns its response (null for a
 * notification), so stdio ([main]) and an HTTP endpoint can share it.
 */
class LiveryMcp(
  /** The catalog source tree its tools read (and an extension may read too). */
  val tree: SourceTree,
  private val writable: Boolean = false,
  private val previewOut: File? = null,
  private val extensions: List<McpExtension> = emptyList(),
) {
  private val json = Json { prettyPrint = true; encodeDefaults = false }
  private val history = GitHistory(tree.root)

  fun handle(message: JsonObject): JsonObject? {
    val id = message["id"] ?: return null
    fun result(value: JsonElement) = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", value) }
    fun error(code: Int, reason: String) = buildJsonObject {
      put("jsonrpc", "2.0"); put("id", id)
      putJsonObject("error") { put("code", code); put("message", reason) }
    }
    if (message["jsonrpc"] != JsonPrimitive("2.0")) return error(-32600, "Invalid request")
    return when ((message["method"] as? JsonPrimitive)?.contentOrNull) {
      "initialize" -> result(buildJsonObject {
        put("protocolVersion", PROTOCOL_VERSION)
        putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
        putJsonObject("serverInfo") { put("name", "livery"); put("version", "1") }
        put("instructions", INSTRUCTIONS)
      })
      "ping" -> result(JsonObject(emptyMap()))
      "tools/list" -> result(buildJsonObject {
        put("tools", kotlinx.serialization.json.JsonArray(Tools.list(writable, previewOut != null) + extensions.flatMap { it.tools }))
      })
      "tools/call" -> {
        val params = message["params"] as? JsonObject ?: return error(-32602, "Missing parameters")
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull ?: return error(-32602, "Missing tool name")
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        result(call(name, args))
      }
      null -> error(-32600, "Invalid request")
      else -> error(-32601, "Method not found")
    }
  }

  /** One tool call, as MCP content: a tool's own failure is a result with `isError`, never a crash. */
  fun call(name: String, args: JsonObject): JsonObject = try {
    when (name) {
      "list_contracts" -> text(listContracts())
      "get_contract" -> text(json.encodeToJsonElement(Contract.serializer(), contract(args.str("contract"))))
      "list_designs" -> text(listDesigns(args.strOrNull("contract")))
      "validate_data" -> text(validateData(contract(args.str("contract")), args.obj("data")))
      "validate_template" -> text(validateTemplate(args))
      "render_preview" -> renderPreview(args)
      "submit_draft" -> if (writable) text(submitDraft(args)) else failed("this server is read-only: start it with --write to accept drafts")
      "build_preview" -> previewOut?.let { text(buildPreview(it, args.str("channel"))) } ?: failed("no preview output: start the server with --preview-out <dir>")
      "publish_preview" -> previewOut?.let { text(publishPreview(it, args)) } ?: failed("no preview output: start the server with --preview-out <dir>")
      "list_history" -> text(listHistory(args))
      "check_compatibility" -> text(checkCompatibility(args))
      else -> extensions.firstOrNull { e -> e.tools.any { (it["name"] as? JsonPrimitive)?.content == name } }
        ?.call(name, args, this) ?: failed("unknown tool $name")
    }
  } catch (e: ToolError) {
    failed(e.message!!)
  } catch (e: CatalogProblems) {
    failed(e.message!!)
  } catch (e: kotlinx.serialization.SerializationException) {
    failed("invalid JSON in the catalog or the arguments: ${e.message}")
  } catch (e: IllegalArgumentException) {
    failed(e.message ?: "invalid arguments")
  } catch (e: Exception) {
    // A tool's failure is the tool's result: it never takes the server down.
    failed("$name failed: ${e.message ?: e::class.simpleName}")
  }

  // --- tools ------------------------------------------------------------------------------------

  private fun listContracts() = buildJsonArray {
    for (c in tree.contracts()) add(buildJsonObject {
      put("contract", c.ref); put("minor", c.minor); c.title?.let { put("title", it) }
      put("dataSource", c.dataSource.name)
      putJsonArray("inputs") { c.inputs.keys.forEach { add(it) } }
      putJsonArray("lists") { c.lists.keys.forEach { add(it) } }
      putJsonArray("actions") { c.actions.keys.forEach { add(it) } }
      putJsonArray("examples") { c.examples.keys.forEach { add(it) } }
    })
  }

  private fun listDesigns(contract: String?) = buildJsonArray {
    for (d in tree.designs().filter { contract == null || it.design.contract == contract }) add(buildJsonObject {
      put("design", json.encodeToJsonElement(Design.serializer(), d.design))
      putJsonArray("templates") {
        for (t in d.templates) add(buildJsonObject {
          put("name", t.name); put("bytes", t.rc.length())
          put("target", json.encodeToJsonElement(ee.schimke.livery.protocol.Target.serializer(), t.spec.target))
          putJsonArray("reads") { t.spec.reads.forEach { add(it) } }
          putJsonArray("emits") { t.spec.emits.forEach { add(it) } }
        })
      }
    })
  }

  private fun validateData(c: Contract, data: JsonObject) = when (val r = ContractBinder.bind(c, data)) {
    is BindResult.Ok -> buildJsonObject {
      put("ok", true)
      putJsonObject("values") { r.bindings.values.forEach { (k, v) -> put(k, v.describe()) } }
    }
    is BindResult.Invalid -> buildJsonObject {
      put("ok", false)
      putJsonArray("problems") { r.problems.forEach { add("${it.name}: ${it.message}") } }
    }
  }

  private fun validateTemplate(args: JsonObject): JsonObject {
    val c = contract(args.str("contract"))
    val bytes = args.bytes("rc_base64")
    val facts = TemplateInspector.facts(bytes) ?: return buildJsonObject {
      put("ok", false); putJsonArray("problems") { add("not a Remote Compose document") }
    }
    val problems = buildList {
      val reads = facts.declaredReads.filterNot { CatalogBuilder.declares(c, it) }
      if (reads.isNotEmpty()) add("reads values outside ${c.ref}: ${reads.sorted()}")
      val emits = facts.emits.filterNot { it in c.actions }
      if (emits.isNotEmpty()) add("emits actions ${c.ref} doesn't declare: ${emits.sorted()}")
      addAll(TemplateInspector.typeProblems(bytes, c))
      args.intOrNull("max_bytes")?.let { if (facts.bytes > it) add("${facts.bytes} bytes, over the host's budget of $it") }
    }
    return buildJsonObject {
      put("ok", problems.isEmpty()); put("bytes", facts.bytes)
      // Apps on an older minor of the contract won't be offered it (PROTOCOL.md §4.3).
      put("requiresMinor", ContractEvolution.requiresMinor(c, facts.declaredReads, facts.emits))
      putJsonArray("reads") { facts.reads.sorted().forEach { add(it) } }
      putJsonArray("emits") { facts.emits.sorted().forEach { add(it) } }
      putJsonArray("problems") { problems.forEach { add(it) } }
      // What to declare: the host's own names (a Wear container's frame) are never the sidecar's.
      put("sidecar", buildJsonObject {
        putJsonArray("reads") { facts.declaredReads.sorted().forEach { add(it) } }
        putJsonArray("emits") { facts.emits.sorted().forEach { add(it) } }
      })
    }
  }

  private fun renderPreview(args: JsonObject): JsonObject {
    val (bytes, c) = when {
      args.strOrNull("rc_base64") != null -> args.bytes("rc_base64") to contract(args.str("contract"))
      else -> {
        val d = tree.design(args.str("design")) ?: throw ToolError("unknown design ${args.str("design")}")
        val t = d.templates.firstOrNull { it.name == args.str("template") }
          ?: throw ToolError("design ${d.design.id} has no template ${args.str("template")}: ${d.templates.map { it.name }}")
        // An earlier version, from the catalog's git history.
        val bytes = args.strOrNull("revision")?.let { rev ->
          if (!history.available) throw ToolError("the catalog isn't a git checkout: no history to render from")
          runCatching { history.show(t.rc, rev) }.getOrElse { throw ToolError(it.message ?: "bad revision") }
            ?: throw ToolError("${d.design.id}/${t.name}.rc didn't exist at $rev")
        } ?: t.rc.readBytes()
        bytes to contract(d.design.contract)
      }
    }
    val data = args["data"] as? JsonObject ?: args.strOrNull("example")?.let { name ->
      c.examples[name] ?: throw ToolError("${c.ref} has no example '$name': ${c.examples.keys}")
    } ?: c.examples.values.firstOrNull() ?: JsonObject(emptyMap())
    val bindings = when (val r = ContractBinder.bind(c, data)) {
      is BindResult.Ok -> r.bindings
      is BindResult.Invalid -> throw ToolError("the data doesn't bind to ${c.ref}: ${r.problems.map { "${it.name}: ${it.message}" }}")
    }
    val spec = runCatching {
      RenderSpec(
        widthDp = args.intOrNull("width_dp") ?: 360, heightDp = args.intOrNull("height_dp") ?: 200,
        density = args.intOrNull("density")?.toFloat() ?: 2f, dark = args.boolOrNull("dark") ?: false,
      )
    }.getOrElse { throw ToolError("width_dp and height_dp must be 1 to 4096, density 1 to 4") }
    TemplateInspector.facts(bytes) ?: throw ToolError("not a Remote Compose document")
    val png = try {
      TemplateRenderer.png(bytes, bindings, spec)
    } catch (e: Throwable) {
      // The renderer is Skia: on a headless host its native library can fail to load (on Linux it
      // links libGL, libfontconfig and libX11). Say so, so an agent can tell its user what to fix
      // instead of retrying.
      val link = generateSequence(e) { it.cause }.firstOrNull { it is UnsatisfiedLinkError } ?: throw e
      throw ToolError("can't render on this host: Skia's native library didn't load (${link.message}). " +
        "On Linux, install libGL and fontconfig (e.g. apt-get install libgl1 libfontconfig1) or put them on LD_LIBRARY_PATH.")
    }
    return buildJsonObject {
      putJsonArray("content") {
        add(buildJsonObject { put("type", "image"); put("data", Base64.getEncoder().encodeToString(png)); put("mimeType", "image/png") })
        add(buildJsonObject {
          put("type", "text")
          put("text", "${spec.widthDp}x${spec.heightDp} dp at ${spec.density}x, ${if (spec.dark) "dark" else "light"}, with ${bindings.values.keys.sorted()}")
        })
      }
    }
  }

  /**
   * Writes a template (and, for a new design, its `design.json`) into the tree: the draft is
   * inspected (it reads and emits only what its sidecar declares), then the whole catalog is checked
   * the way `livery build` does. A draft that fails either is taken back out, so the tree only ever
   * holds drafts that pass the gate.
   */
  private fun submitDraft(args: JsonObject): JsonObject {
    val designId = args.str("design").also { if (!SourceTree.safeName(it)) throw ToolError("bad design id '$it'") }
    val name = args.str("template").also {
      if (!SourceTree.safeName(it)) throw ToolError("bad template name '$it'")
      // Its sidecar would be written over the design's own metadata.
      if (it == "design") throw ToolError("'design' is reserved: design.json is the design's metadata")
    }
    val bytes = args.bytes("rc_base64")
    val spec = json.decodeFromJsonElement(TemplateSpec.serializer(), args.obj("sidecar"))
    val dir = File(File(tree.root, "designs"), designId)
    val designFile = File(dir, "design.json")
    val newDesign = args["design_json"] as? JsonObject
    if (!designFile.isFile && newDesign == null) throw ToolError("design $designId doesn't exist: pass design_json to create it")
    val writes = buildMap {
      newDesign?.let {
        val d = json.decodeFromJsonElement(Design.serializer(), it)
        if (d.id != designId) throw ToolError("design_json id '${d.id}' must be '$designId'")
        put(designFile, json.encodeToString(Design.serializer(), d).encodeToByteArray())
      }
      put(File(dir, "$name.rc"), bytes)
      put(File(dir, "$name.json"), json.encodeToString(TemplateSpec.serializer(), spec).encodeToByteArray())
    }
    // The draft itself is read, not trusted: what it reads and emits must be what its sidecar says.
    TemplateInspector.problems(bytes, spec.reads, spec.emits).takeIf { it.isNotEmpty() }?.let { problems ->
      return buildJsonObject {
        put("ok", false)
        putJsonArray("problems") { problems.forEach { add("$designId/$name.rc: $it") } }
        put("note", "nothing was written; validate_template returns the reads and emits to declare")
      }
    }
    val before = writes.keys.associateWith { f -> f.takeIf { it.isFile }?.readBytes() }
    dir.mkdirs()
    writes.forEach { (f, b) -> f.writeBytes(b) }
    val manifest = try {
      CatalogBuilder().build(tree.root, Layout.Static, serial = 0).manifest
    } catch (e: Exception) {
      // Any failure (catalog problems, a sidecar or design that doesn't decode) puts the tree back.
      before.forEach { (f, b) -> if (b == null) f.delete() else f.writeBytes(b) }
      if (dir.listFiles().isNullOrEmpty()) dir.delete()
      val problems = (e as? CatalogProblems)?.problems ?: listOf(e.message ?: e::class.simpleName.orEmpty())
      return buildJsonObject {
        put("ok", false)
        putJsonArray("problems") { problems.forEach { add(it) } }
        put("note", "nothing was written: the draft would leave the catalog with problems")
      }
    }
    return buildJsonObject {
      put("ok", true)
      putJsonArray("written") { writes.keys.forEach { add(it.relativeTo(tree.root).path) } }
      put("templates", manifest.templates.count { it.designId == designId })
      put("next", "render_preview with design=$designId template=$name to look at it; commit the files for review")
    }
  }

  /** An unsigned catalog for a preview channel, never the catalog's own (production) channel. */
  private fun buildPreview(out: File, channel: String): JsonObject {
    if (!SourceTree.safeName(channel)) throw ToolError("bad channel '$channel'")
    val config = Json { ignoreUnknownKeys = true }.decodeFromString(CatalogConfig.serializer(), File(tree.root, "catalog.json").readText())
    if (channel == config.channel) throw ToolError("'$channel' is the catalog's own channel: production catalogs are built and signed by CI, not an agent")
    val built = CatalogBuilder().build(tree.root, Layout.Static)
    val manifest = built.manifest.copy(channel = channel)
    val dir = File(out, channel).apply { deleteRecursively(); mkdirs() }
    built.files.forEach { (path, b) -> File(dir, path).apply { parentFile.mkdirs() }.writeBytes(b) }
    File(dir, Manifest.FILE).writeText(json.encodeToString(Manifest.serializer(), manifest))
    return buildJsonObject {
      put("ok", true); put("path", dir.path); put("channel", channel); put("serial", manifest.serial)
      put("signed", false)
      put("note", "unsigned: only a client that allows unsigned catalogs (a debug build) will play it")
    }
  }

  /** One document as its own unsigned catalog ([PreviewCatalog]), checked against its contract. */
  private fun publishPreview(out: File, args: JsonObject): JsonObject {
    val c = contract(args.str("contract"))
    val target = (args["target"] as? JsonObject)?.let { json.decodeFromJsonElement(ee.schimke.livery.protocol.Target.serializer(), it) }
    val source = (args["source"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content ?: it.value.toString() }.orEmpty()
    val bytes = args.bytes("rc_base64")
    val result = if (target != null) PreviewCatalog.build(c, bytes, target, args.strOrNull("title"), source)
      else PreviewCatalog.build(c, bytes, title = args.strOrNull("title"), source = source)
    val dir = PreviewCatalog.write(result, out)
    return buildJsonObject {
      put("ok", true); put("channel", result.channel); put("path", dir.path); put("signed", false)
      put("design", result.output.manifest.designs.single().id)
      put("note", "a catalog of this one document: point a debug build's repository at ${dir.path} (or wherever you serve it)")
    }
  }

  /**
   * The catalog against an earlier version of itself ([CompatibilityCheck]): a git revision of its
   * repository (HEAD by default: what this working tree changes) or another source tree.
   */
  private fun checkCompatibility(args: JsonObject): JsonElement {
    val against = args.strOrNull("against") ?: "HEAD"
    if (!File(against).isDirectory && !history.available) throw ToolError("the catalog isn't a git checkout: pass another source tree as 'against'")
    val report = CompatibilityCheck.check(tree.root, against, inspect = args.boolOrNull("inspect") ?: false)
    return json.encodeToJsonElement(CompatibilityCheck.Report.serializer(), report).let { r ->
      JsonObject((r as JsonObject) + ("ok" to JsonPrimitive(report.ok)))
    }
  }

  /** The commits that changed a design's templates, newest first, from git. */
  private fun listHistory(args: JsonObject): JsonElement {
    if (!history.available) throw ToolError("the catalog isn't a git checkout: no history")
    val d = tree.design(args.str("design")) ?: throw ToolError("unknown design ${args.str("design")}")
    val only = args.strOrNull("template")
    val limit = args.intOrNull("limit") ?: 20
    return buildJsonArray {
      for (t in d.templates.filter { only == null || it.name == only }) add(buildJsonObject {
        put("template", t.name)
        t.spec.source.takeIf { it.isNotEmpty() }?.let { s -> putJsonObject("source") { s.forEach { (k, v) -> put(k, v) } } }
        putJsonArray("changes") {
          for (ch in history.log(t.rc, limit)) add(buildJsonObject {
            put("revision", ch.commit); put("date", ch.dateEpochSeconds); put("author", ch.author); put("subject", ch.subject)
          })
        }
      })
    }
  }

  // --- helpers ----------------------------------------------------------------------------------

  private fun contract(ref: String) = tree.contract(ref) ?: throw ToolError("unknown contract $ref: ${tree.contracts().map { it.ref }}")

  private class ToolError(message: String) : Exception(message)

  private fun text(value: JsonElement) = buildJsonObject {
    putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", json.encodeToString(JsonElement.serializer(), value)) }) }
  }

  private fun failed(reason: String) = buildJsonObject {
    putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", reason) }) }
    put("isError", true)
  }

  private fun JsonObject.strOrNull(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
  private fun JsonObject.str(key: String) = strOrNull(key)?.takeIf { it.isNotBlank() } ?: throw ToolError("missing argument '$key'")
  private fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: throw ToolError("argument '$key' must be a JSON object")
  private fun JsonObject.intOrNull(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
  private fun JsonObject.boolOrNull(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
  private fun JsonObject.bytes(key: String): ByteArray =
    runCatching { Base64.getDecoder().decode(str(key)) }.getOrElse { throw ToolError("'$key' is not base64") }
      .also { if (it.size > MAX_TEMPLATE_BYTES) throw ToolError("${it.size} bytes: a template over $MAX_TEMPLATE_BYTES bytes is refused") }

  private fun NamedValue.describe(): JsonElement = when (this) {
    is NamedValue.Str -> JsonPrimitive(value)
    is NamedValue.I -> JsonPrimitive(value)
    is NamedValue.L -> JsonPrimitive(value)
    is NamedValue.F -> JsonPrimitive(value)
    is NamedValue.B -> JsonPrimitive(value)
    is NamedValue.Color -> JsonPrimitive("#%08X".format(argb))
    is NamedValue.Image -> JsonPrimitive(ref)
  }

  companion object {
    const val PROTOCOL_VERSION: String = "2025-03-26"
    private const val MAX_TEMPLATE_BYTES = 2 * 1024 * 1024

    val INSTRUCTIONS: String =
      "You work on a Livery template catalog: contracts say what a template may read and emit, designs " +
        "are looks for a contract, templates are Remote Compose documents per target. Start with " +
        "list_contracts and get_contract. Prefer data to documents: for copy, images or a campaign, " +
        "write data and check it with validate_data. For a new or changed document, validate_template " +
        "first (it reads the document itself), then render_preview with the contract's examples (and " +
        "dark=true) to look at it as a person would, check_compatibility to see what it changes for apps " +
        "already installed, then submit_draft when the server accepts writes. " +
        "Drafts are unsigned; a person reviews them, and production catalogs are signed by CI, never by you."
  }
}

