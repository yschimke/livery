@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.livery.capture

import android.content.Context
import androidx.compose.remote.creation.compose.action.Action
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.RemoteDensity
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.state.RemoteBoolean
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteInt
import androidx.compose.remote.creation.compose.state.RemoteLong
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.profile.Profile
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ee.schimke.composeai.rcwidget.creation.rcWidgetHostAction
import ee.schimke.composeai.rcwidget.launcher.widgetsProfile
import ee.schimke.livery.client.ContractBinder
import ee.schimke.livery.client.ContractEvolution
import ee.schimke.livery.instantiate.TemplateInspector
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.InputSpec
import ee.schimke.livery.protocol.Target
import ee.schimke.livery.protocol.ValueType
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * A template's named values and actions, checked against its [contract] as the composable asks
 * for them: a name the contract doesn't declare, or the wrong type, fails the capture, not the
 * publish. Defaults come from the contract, so the captured document draws them when data leaves
 * a value out.
 *
 * ```
 * val v = LiveryValues(contract)
 * Text(v.string("title"))
 * if (v.bool("is_on") …) …
 * Button(onClick = v.action("toggle")) { … }
 * ```
 *
 * A list's values are `"<list>.<index>.<field>"` and `"<list>.count"`; theme roles are `theme.*`
 * colours, filled by the host.
 */
class LiveryValues(val contract: Contract) {
  fun string(name: String): RemoteString =
    RemoteString.createNamedRemoteString(name, default(name, ValueType.String)?.contentOrEmpty() ?: "", USER)

  fun int(name: String): RemoteInt =
    RemoteInt.createNamedRemoteInt(name, (default(name, ValueType.Int, countOk = true) as? JsonPrimitive)?.longOrNull?.toInt() ?: 0, USER)

  fun long(name: String): RemoteLong =
    RemoteLong.createNamedRemoteLong(name, (default(name, ValueType.Long) as? JsonPrimitive)?.longOrNull ?: 0, USER)

  fun float(name: String): RemoteFloat =
    RemoteFloat.createNamedRemoteFloat(name, (default(name, ValueType.Float) as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: 0f, USER)

  fun bool(name: String): RemoteBoolean =
    RemoteBoolean.createNamedRemoteBoolean(name, (default(name, ValueType.Bool) as? JsonPrimitive)?.booleanOrNull ?: false, USER)

  /** A colour input or a `theme.*` role; [fallback] draws when the contract has no default. */
  fun color(name: String, fallback: Color = Color.Black): RemoteColor {
    val argb = (default(name, ValueType.Color, themeOk = true) as? JsonPrimitive)?.contentOrEmpty()?.let(ContractBinder::parseColor)
    return RemoteColor.createNamedRemoteColor(name, argb?.let { Color(it.toInt()) } ?: fallback, USER)
  }

  /** A host action the contract declares, emitted on tap with its name as the payload. */
  @Composable
  fun action(name: String): Action {
    require(name in contract.actions) { "${contract.ref} declares no action '$name': ${contract.actions.keys.sorted()}" }
    return rcWidgetHostAction(name = name, payload = name)
  }

  private fun default(name: String, type: ValueType, countOk: Boolean = false, themeOk: Boolean = false): JsonElement? {
    if (themeOk && name.startsWith("theme.") && ContractEvolution.specOf(contract, name) == null) return null
    if (countOk && contract.lists.keys.any { name == "$it.count" }) return null
    val spec: InputSpec = ContractEvolution.specOf(contract, name)
      ?: throw IllegalArgumentException("${contract.ref} declares no value '$name'")
    require(spec.type == type) { "${contract.ref} declares '$name' as ${spec.type.name.lowercase()}, not ${type.name.lowercase()}" }
    return spec.default
  }

  private fun JsonElement.contentOrEmpty(): String = (this as? JsonPrimitive)?.content ?: ""

  private companion object {
    val USER = RemoteState.Domain.User
  }
}

/** One captured template: its bytes and the sidecar Livery's catalog reads beside it. */
class CapturedTemplate(val bytes: ByteArray, val target: Target, val reads: List<String>, val emits: List<String>, val source: Map<String, String>) {
  /** The `<name>.json` sidecar, as `livery build` reads it. */
  fun sidecar(): String = JSON.encodeToString(JsonObject.serializer(), buildJsonObject {
    put("target", JSON.encodeToJsonElement(Target.serializer(), target))
    put("reads", buildJsonArray { reads.forEach { add(JsonPrimitive(it)) } })
    put("emits", buildJsonArray { emits.forEach { add(JsonPrimitive(it)) } })
    if (source.isNotEmpty()) put("source", buildJsonObject { source.forEach { (k, v) -> put(k, v) } })
  }) + "\n"

  private companion object {
    val JSON = Json { prettyPrint = true; encodeDefaults = false }
  }
}

/**
 * Captures a `@RemoteComposable` for a [Target] (AndroidX remote-creation-compose), from a unit test
 * (Robolectric) or a build tool. The sidecar's reads and emits come from the captured document
 * itself, so they can't drift from what the template does.
 */
object LiveryCapture {
  /** The newest minor of [ref] (`id@major`) in a catalog source tree's `contracts/`. */
  fun contract(catalog: File, ref: String): Contract =
    File(catalog, "contracts").listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.name }
      .map { CONTRACTS.decodeFromString(Contract.serializer(), it.readText()) }
      .filter { it.ref == ref }.maxByOrNull { it.minor }
      ?: throw IllegalArgumentException("no contract $ref in ${File(catalog, "contracts")}")

  private val CONTRACTS = Json { ignoreUnknownKeys = true }

  /**
   * Captures [content] at [widthDp] × [heightDp] (a size inside [target]'s range) for [target].
   * [profile] defaults to the widgets profile for a `widgets` target and the creation default
   * otherwise. [source] goes into the sidecar (`tool` is `capture` unless it names another).
   */
  suspend fun capture(
    context: Context,
    target: Target,
    widthDp: Int,
    heightDp: Int,
    source: Map<String, String> = emptyMap(),
    profile: Profile? = if ("widgets" in target.profiles) widgetsProfile else null,
    content: @Composable () -> Unit,
  ): CapturedTemplate {
    val display = RemoteCreationDisplayInfo(widthDp, heightDp, 160)
    val bytes = if (profile != null) {
      captureSingleRemoteDocument(context = context, creationDisplayInfo = display, remoteDensity = RemoteDensity.Host, profile = profile, content = content).bytes
    } else {
      captureSingleRemoteDocument(context = context, creationDisplayInfo = display, remoteDensity = RemoteDensity.Host, content = content).bytes
    }
    val facts = requireNotNull(TemplateInspector.facts(bytes)) { "the capture isn't a Remote Compose document" }
    return CapturedTemplate(bytes, target, facts.declaredReads.sorted(), facts.emits.sorted(), mapOf("tool" to "capture") + source)
  }
}

/**
 * A design's folder in a catalog source tree (`designs/<id>/`): the templates a capture produced,
 * written or checked. Keep a unit test that [check]s it, and run it with your regenerate switch to
 * [write] instead after changing a composable:
 *
 * ```
 * val folder = DesignFolder(File(catalog, "designs/tile-default"))
 * if (regenerate) folder.write(templates) else assertEquals(emptyList(), folder.check(templates))
 * ```
 */
class DesignFolder(val dir: File) {
  /** Writes `<name>.rc` and `<name>.json` for each template, and removes templates no longer captured. */
  fun write(templates: Map<String, CapturedTemplate>) {
    requireNames(templates)
    dir.mkdirs()
    stale(templates).forEach { File(dir, it).delete() }
    for ((name, t) in templates) {
      File(dir, "$name.rc").writeBytes(t.bytes)
      File(dir, "$name.json").writeText(t.sidecar())
    }
  }

  /** What differs from the committed files; empty when they are current. */
  fun check(templates: Map<String, CapturedTemplate>): List<String> = buildList {
    requireNames(templates)
    for ((name, t) in templates) {
      val rc = File(dir, "$name.rc")
      val json = File(dir, "$name.json")
      when {
        !rc.isFile -> add("$name.rc is missing")
        !rc.readBytes().contentEquals(t.bytes) -> add("$name.rc differs from the capture")
      }
      when {
        !json.isFile -> add("$name.json is missing")
        json.readText() != t.sidecar() -> add("$name.json differs from the capture")
      }
    }
    stale(templates).forEach { add("$it is no longer captured") }
  }

  private fun requireNames(templates: Map<String, CapturedTemplate>) {
    for (name in templates.keys) {
      require(NAME.matches(name)) { "template names are ${NAME.pattern}: '$name'" }
      require(name !in RESERVED) { "'$name' is reserved in a design's folder (design.json, previews/, assets/)" }
    }
  }

  private companion object {
    val NAME = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    val RESERVED = setOf("design", "previews", "assets")
  }

  private fun stale(templates: Map<String, CapturedTemplate>): List<String> =
    dir.listFiles().orEmpty().filter { (it.extension == "rc" || (it.extension == "json" && it.name != "design.json")) && it.nameWithoutExtension !in templates }.map { it.name }.sorted()
}
