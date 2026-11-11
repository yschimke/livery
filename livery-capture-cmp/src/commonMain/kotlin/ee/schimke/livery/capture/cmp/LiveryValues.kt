package ee.schimke.livery.capture.cmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import ee.schimke.composeai.rccreation.compose.action.RemoteAction
import ee.schimke.composeai.rccreation.compose.action.hostAction
import ee.schimke.composeai.rccreation.compose.state.RemoteBoolean
import ee.schimke.composeai.rccreation.compose.state.RemoteColor
import ee.schimke.composeai.rccreation.compose.state.RemoteFloat
import ee.schimke.composeai.rccreation.compose.state.RemoteInt
import ee.schimke.composeai.rccreation.compose.state.RemoteLong
import ee.schimke.composeai.rccreation.compose.state.RemoteState
import ee.schimke.composeai.rccreation.compose.state.RemoteString
import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.composeai.rcwidget.creation.rcWidgetHostAction
import ee.schimke.livery.capture.ContractChecks
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.ValueType

// One source for both bindings: livery-capture generates its AndroidX LiveryValues from this file
// by rewriting the packages (its generateAndroidxBinding task), so write only what both creation
// APIs spell the same way, and keep what a contract means in livery-capture-core's ContractChecks.

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
  private val checks = ContractChecks(contract)

  fun string(name: String): RemoteString = RemoteString.createNamedRemoteString(name, checks.string(name), USER)

  fun int(name: String): RemoteInt = RemoteInt.createNamedRemoteInt(name, checks.int(name), USER)

  fun long(name: String): RemoteLong = RemoteLong.createNamedRemoteLong(name, checks.long(name), USER)

  fun float(name: String): RemoteFloat = RemoteFloat.createNamedRemoteFloat(name, checks.float(name), USER)

  fun bool(name: String): RemoteBoolean = RemoteBoolean.createNamedRemoteBoolean(name, checks.bool(name), USER)

  /** A colour input or a `theme.*` role; [fallback] draws when the contract has no default. */
  fun color(name: String, fallback: Color = Color.Black): RemoteColor =
    RemoteColor.createNamedRemoteColor(name, checks.color(name) ?: fallback.toArgb(), USER)

  /**
   * A host action the contract declares, emitted on tap. With [args], the payload is their JSON
   * object; each must be an argument the action declares, of its type, with every required one
   * given (the client checks the same, PROTOCOL.md §7). With none, the payload is the action's name.
   *
   * A `track` action reports an interaction that changes only the template's own state; combine it
   * with the change in one `combinedAction`, as `clickable(combinedAction(<the change>, v.action("expand")))`.
   */
  @Composable
  fun action(name: String, args: Map<String, String> = emptyMap()): RemoteAction =
    rcWidgetHostAction(name = name, payload = checks.actionPayload(name, args))

  /**
   * The `set` action [name] (PROTOCOL.md §7.1), sending [value] as the new value of the input it
   * names: the person changed it here, and the host writes it back to wherever that input comes
   * from (an A2UI data model, the app's own state). [value] is evaluated when tapped, so a stepper's
   * plus is `v.set("setQuantity", v.int("quantity") + 1)`.
   */
  fun set(name: String, value: RemoteInt): RemoteAction = setAction(name, { n -> hostAction(n, value) }, ValueType.Int, ValueType.Long)

  fun set(name: String, value: RemoteFloat): RemoteAction = setAction(name, { n -> hostAction(n, value) }, ValueType.Float)

  fun set(name: String, value: RemoteString): RemoteAction = setAction(name, { n -> hostAction(n, value) }, ValueType.String)

  /** A bool reaches the host as `1` or `0`, which the client takes as `true` or `false`. */
  fun set(name: String, value: RemoteBoolean): RemoteAction = setAction(name, { n -> hostAction(n, value.toRemoteInt()) }, ValueType.Bool)

  private fun setAction(name: String, action: (RemoteString) -> RemoteAction, vararg types: ValueType): RemoteAction {
    checks.requireSet(name, *types)
    return action(name.rs)
  }

  private companion object {
    val USER = RemoteState.Domain.User
  }
}
