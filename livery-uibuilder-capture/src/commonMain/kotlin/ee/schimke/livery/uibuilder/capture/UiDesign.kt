package ee.schimke.livery.uibuilder.capture

import androidx.compose.runtime.Composable
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxHeight
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxSize
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rccreation.compose.modifier.height
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.modifier.size
import ee.schimke.composeai.rccreation.compose.modifier.width
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.livery.capture.cmp.LiveryValues
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * How a component draws one node of a UI Builder design: [node]'s properties and slots, with
 * [modifier] (its parent's, then the node's own modifiers) on its root.
 */
typealias UiComponent = @Composable (node: UiNode, modifier: RemoteModifier) -> Unit

/**
 * A Compose UI Builder document (`.uid`, `compose-ui-builder-document/v1`), drawn as Remote Compose
 * with [components]: one per `componentId` its nodes use, from the app's UI Builder catalog, over
 * [UiLayouts.components] (the frame's rows, columns and boxes).
 *
 * With [values], each property bound to a `"persistence": "host"` state variable reads the
 * contract's value of that name ([UiNode.remoteString] and the rest), so a design that reads a
 * value its contract doesn't declare, or as the wrong type, fails as a Compose capture does.
 * Without, a design previews with its variables' initial values as named values.
 *
 * ```
 * val design = UiDesign.parse(File("kitchen.uid").readText(), myComponents)
 * LiveryCapture.capture(target, design.widthDp, design.heightDp) { design.Render(LiveryValues(contract)) }
 * ```
 */
class UiDesign(val document: JsonObject, val components: Map<String, UiComponent>) {
  val id: String = requireNotNull(document["id"]?.jsonPrimitive?.contentOrNull) { "a .uid document has an id" }
  val title: String? = document["title"]?.jsonPrimitive?.contentOrNull
  val revision: Long? = document["revision"]?.jsonPrimitive?.longOrNull

  /** The UI Builder catalog the design is built from (`catalogPin.systemId`), when pinned. */
  val systemId: String? = (document["catalogPin"] as? JsonObject)?.get("systemId")?.jsonPrimitive?.contentOrNull

  private val environment = document["environment"] as? JsonObject
  val widthDp: Int = environment?.get("widthDp")?.jsonPrimitive?.floatOrNull?.toInt() ?: 360
  val heightDp: Int = environment?.get("heightDp")?.jsonPrimitive?.floatOrNull?.toInt() ?: 240

  /** The design's state variables, by name: `type`, `valueType`, `initialValue`, `persistence`. */
  val stateVariables: Map<String, JsonObject> =
    (document["stateVariables"] as? JsonObject).orEmpty().mapValues { it.value.jsonObject }

  /** The state variables a host fills (`"persistence": "host"`): the contract inputs the design reads. */
  val hostInputs: Set<String> =
    stateVariables.filterValues { it["persistence"]?.jsonPrimitive?.contentOrNull == HOST }.keys

  private val nodes: Map<String, JsonObject> = (document["nodes"] as? JsonObject).orEmpty().mapValues { it.value.jsonObject }
  private val roots: List<String> = (document["roots"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
  private val all = UiLayouts.components + components

  internal fun componentOf(id: String): String? = nodes[id]?.get("componentId")?.jsonPrimitive?.contentOrNull

  internal fun childrenOf(id: String, slot: String? = null): List<String> {
    val slots = nodes[id]?.get("slots") as? JsonObject ?: return emptyList()
    val picked = if (slot == null) slots.values else listOfNotNull(slots[slot])
    return picked.flatMap { it.jsonArray.map { child -> child.jsonPrimitive.content } }
  }

  /**
   * What this design can't draw, each with its node's id: a component with none registered, a
   * modifier this interpreter doesn't apply, a state binding to no declared variable, a missing
   * child. Empty when it draws as written.
   */
  fun validate(): List<String> = buildList {
    if (roots.size != 1) add("a design has one root, not ${roots.size}")
    roots.filter { it !in nodes }.forEach { add("root $it is not a node") }
    for ((id, node) in nodes) {
      val component = componentOf(id)
      if (component !in all) add("$id: no component `$component` is registered")
      (node["modifiers"] as? JsonArray).orEmpty().forEach { m ->
        val type = (m as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull
        if (type !in MODIFIERS) add("$id: modifier `$type` is not applied here")
      }
      (node["properties"] as? JsonObject).orEmpty().forEach { (name, value) ->
        val p = value as? JsonObject
        if (p == null) {
          add("$id.$name: a property is an object")
          return@forEach
        }
        when (val type = p["type"]?.jsonPrimitive?.contentOrNull) {
          STATE -> {
            val variable = p["variable"]?.jsonPrimitive?.contentOrNull
            if (variable !in stateVariables) add("$id.$name: state `$variable` is not declared")
          }
          !in LITERALS -> add("$id.$name: a `$type` property is not drawn here")
        }
      }
      childrenOf(id).filter { it !in nodes }.forEach { add("$id: missing child $it") }
    }
  }

  /** The design, its root drawn with [modifier], host inputs read from [values] when given. */
  @Composable
  fun Render(values: LiveryValues? = null, modifier: RemoteModifier = RemoteModifier) {
    val problems = validate()
    check(problems.isEmpty()) { "$id can't be drawn: ${problems.joinToString("; ")}" }
    Node(roots.single(), modifier, values)
  }

  @Composable
  internal fun Node(id: String, modifier: RemoteModifier, values: LiveryValues?) {
    val node = nodes.getValue(id)
    var applied = modifier
    (node["modifiers"] as? JsonArray).orEmpty().forEach { applied = applied.apply(it.jsonObject) }
    val component = all.getValue(componentOf(id)!!)
    component(UiNode(this, id, node["properties"] as? JsonObject ?: JsonObject(emptyMap()), values), applied)
  }

  private fun RemoteModifier.apply(m: JsonObject): RemoteModifier {
    fun dp(name: String) = m[name]?.jsonPrimitive?.floatOrNull
    return when (m["type"]?.jsonPrimitive?.contentOrNull) {
      "fillMaxWidth" -> fillMaxWidth()
      "fillMaxHeight" -> fillMaxHeight()
      "fillMaxSize" -> fillMaxSize()
      "width" -> dp("valueDp")?.let { width(it.rdp) } ?: this
      "height" -> dp("valueDp")?.let { height(it.rdp) } ?: this
      "size" -> {
        val w = dp("widthDp") ?: dp("valueDp")
        val h = dp("heightDp") ?: dp("valueDp")
        if (w != null && h != null) size(w.rdp, h.rdp) else this
      }
      "padding" -> {
        val all = dp("allDp") ?: dp("valueDp") ?: 0f
        padding(
          start = (dp("startDp") ?: dp("horizontalDp") ?: all).rdp,
          top = (dp("topDp") ?: dp("verticalDp") ?: all).rdp,
          end = (dp("endDp") ?: dp("horizontalDp") ?: all).rdp,
          bottom = (dp("bottomDp") ?: dp("verticalDp") ?: all).rdp,
        )
      }
      else -> this
    }
  }

  companion object {
    private const val HOST = "host"
    internal const val STATE = "state"

    /** The modifiers this interpreter applies; `validate` reports any other. */
    val MODIFIERS: Set<String> = setOf("fillMaxWidth", "fillMaxHeight", "fillMaxSize", "width", "height", "size", "padding")

    /** Literal property kinds: an `enum` (an alignment, an arrangement) is its value's string. */
    internal val LITERALS = setOf("string", "bool", "int", "float", "color", "enum")

    private val json = Json { ignoreUnknownKeys = true }

    /** The design in [text], a `.uid` document, drawn with [components]. */
    fun parse(text: String, components: Map<String, UiComponent> = emptyMap()): UiDesign =
      UiDesign(json.parseToJsonElement(text).jsonObject, components)

  }
}
