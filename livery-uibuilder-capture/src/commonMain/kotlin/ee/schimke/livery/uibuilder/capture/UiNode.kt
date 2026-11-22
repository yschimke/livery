package ee.schimke.livery.uibuilder.capture

import androidx.compose.runtime.Composable
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.state.RemoteBoolean
import ee.schimke.composeai.rccreation.compose.state.RemoteFloat
import ee.schimke.composeai.rccreation.compose.state.RemoteInt
import ee.schimke.composeai.rccreation.compose.state.RemoteState
import ee.schimke.composeai.rccreation.compose.state.RemoteString
import ee.schimke.composeai.rccreation.compose.state.rf
import ee.schimke.composeai.rccreation.compose.state.ri
import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.livery.capture.cmp.LiveryValues
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One node of a [UiDesign], as its component draws it: its properties, plain or as Remote Compose
 * values, and its slots.
 *
 * A property is a literal (`{"type":"string","value":…}`, `float`, `int`, `bool`, `color`, `enum`)
 * or bound to a state variable (`{"type":"state","variable":…}`). The plain readers ([string],
 * [float], …) give a bound property's initial value; the `remote*` readers give what plays:
 * - a **host** variable (`"persistence": "host"`) is the contract input of that name, through the
 *   capture's [LiveryValues] (a name or type it doesn't declare fails the capture), or, with no
 *   values, a named value seeded with the variable's initial value;
 * - a **local** variable is its initial value, a constant: a design's own state isn't a contract
 *   input, so it isn't a value a host fills.
 */
class UiNode internal constructor(
  val design: UiDesign,
  val id: String,
  private val properties: JsonObject,
  private val values: LiveryValues?,
) {
  /** This node's `componentId`. */
  val componentId: String get() = design.componentOf(id)!!

  /** Whether [name] is set on this node. */
  fun has(name: String): Boolean = name in properties

  /** The state variable [name] is bound to, or null for a literal or an unset property. */
  fun stateOf(name: String): String? {
    val p = properties[name] as? JsonObject ?: return null
    if (p["type"]?.jsonPrimitive?.contentOrNull != UiDesign.STATE) return null
    return p["variable"]?.jsonPrimitive?.contentOrNull
  }

  /** Whether [name] is bound to a host input: a value the host fills from the contract. */
  fun isHostInput(name: String): Boolean = stateOf(name)?.let { it in design.hostInputs } == true

  private fun literal(name: String): JsonPrimitive? {
    val p = properties[name] as? JsonObject ?: return null
    stateOf(name)?.let { return design.stateVariables[it]?.get("initialValue") as? JsonPrimitive }
    return p["value"] as? JsonPrimitive
  }

  fun string(name: String, default: String = ""): String = literal(name)?.contentOrNull ?: default

  fun bool(name: String, default: Boolean = false): Boolean = literal(name)?.booleanOrNull ?: default

  fun int(name: String, default: Int = 0): Int = literal(name)?.intOrNull ?: literal(name)?.floatOrNull?.toInt() ?: default

  fun float(name: String, default: Float = 0f): Float = literal(name)?.floatOrNull ?: default

  /** An `#AARRGGBB` or `#RRGGBB` literal as ARGB, or null when unset or not a colour. */
  fun argb(name: String): Int? {
    val hex = string(name).removePrefix("#").takeIf { it.length == 6 || it.length == 8 } ?: return null
    val v = hex.toLongOrNull(16) ?: return null
    return (if (hex.length == 6) v or 0xFF000000 else v).toInt()
  }

  /** [name] as it plays: the contract's value for a host input, else its literal. */
  fun remoteString(name: String, default: String = ""): RemoteString {
    val v = hostVariable(name) ?: return string(name, default).rs
    return values?.string(v) ?: RemoteString.createNamedRemoteString(v, string(name, default), USER)
  }

  fun remoteFloat(name: String, default: Float = 0f): RemoteFloat {
    val v = hostVariable(name) ?: return float(name, default).rf
    return values?.float(v) ?: RemoteFloat.createNamedRemoteFloat(v, float(name, default), USER)
  }

  fun remoteInt(name: String, default: Int = 0): RemoteInt {
    val v = hostVariable(name) ?: return int(name, default).ri
    return values?.int(v) ?: RemoteInt.createNamedRemoteInt(v, int(name, default), USER)
  }

  fun remoteBool(name: String, default: Boolean = false): RemoteBoolean {
    val v = hostVariable(name) ?: return RemoteBoolean(bool(name, default))
    return values?.bool(v) ?: RemoteBoolean.createNamedRemoteBoolean(v, bool(name, default), USER)
  }

  private fun hostVariable(name: String): String? = stateOf(name)?.takeIf { it in design.hostInputs }

  /** The capture's contract values, when it has any: for a component that binds more than its properties. */
  val liveryValues: LiveryValues? get() = values

  /** The nodes in [slot], or in every slot in order when null. */
  fun children(slot: String? = null): List<String> = design.childrenOf(id, slot)

  /** The component id of child [childId]. */
  fun componentOf(childId: String): String? = design.componentOf(childId)

  /** Draws each node in [slot] (every slot, in order, when null) with [modifier], then its own modifiers. */
  @Composable
  fun Children(slot: String? = null, modifier: RemoteModifier = RemoteModifier) {
    children(slot).forEach { design.Node(it, modifier, values) }
  }

  /** Draws one child [childId] (one of [children]) with [modifier]. */
  @Composable
  fun Child(childId: String, modifier: RemoteModifier = RemoteModifier) = design.Node(childId, modifier, values)

  private companion object {
    val USER = RemoteState.Domain.User
  }
}
