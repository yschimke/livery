package ee.schimke.livery.capture.cmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import ee.schimke.composeai.rccreation.compose.action.RemoteAction
import ee.schimke.composeai.rccreation.compose.action.hostAction
import ee.schimke.composeai.rccreation.compose.state.RemoteBoolean
import ee.schimke.composeai.rccreation.compose.state.RemoteColor
import ee.schimke.composeai.rccreation.compose.state.RemoteFloat
import ee.schimke.composeai.rccreation.compose.state.RemoteImageBitmap
import ee.schimke.composeai.rccreation.compose.state.RemoteInt
import ee.schimke.composeai.rccreation.compose.state.RemoteLong
import ee.schimke.composeai.rccreation.compose.state.RemoteState
import ee.schimke.composeai.rccreation.compose.state.RemoteString
import ee.schimke.composeai.rccreation.compose.state.rememberRemoteImageBitmap
import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.composeai.rcwidget.creation.rcWidgetHostAction
import ee.schimke.livery.capture.ContractChecks
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.ContractAction
import ee.schimke.livery.protocol.ContractDef
import ee.schimke.livery.protocol.ContractField
import ee.schimke.livery.protocol.ContractInput
import ee.schimke.livery.protocol.ContractList
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
 *
 * Made from a [ContractDef], each value can also be asked for by its handle, so a renamed input
 * fails to compile rather than to capture, and a handle of another contract fails the capture:
 *
 * ```
 * val v = LiveryValues(CardContract)
 * Text(v.string(CardContract.title))
 * for (i in 0 until 3) Text(v.string(CardContract.talks, i, CardContract.talkTitle))
 * Button(onClick = v.action(CardContract.open)) { … }
 * ```
 */
class LiveryValues(val contract: Contract) {
  constructor(def: ContractDef) : this(def.toContract())

  private val checks = ContractChecks(contract)

  private fun own(field: ContractField): String {
    require(field.contract.ref == contract.ref) { "$field is not of ${contract.ref}" }
    return field.name
  }

  fun string(input: ContractInput): RemoteString = string(own(input))

  fun int(input: ContractInput): RemoteInt = int(own(input))

  fun long(input: ContractInput): RemoteLong = long(own(input))

  fun float(input: ContractInput): RemoteFloat = float(own(input))

  fun bool(input: ContractInput): RemoteBoolean = bool(own(input))

  fun color(input: ContractInput, fallback: Color = Color.Black): RemoteColor = color(own(input), fallback)

  /** How many items of [list] are bound (`<list>.count`); 0 until the host sets it. */
  fun count(list: ContractList): RemoteInt = int(list.countName.also { own(list) })

  /** [field] of item [index] of [list] (`<list>.<index>.<field>`): a design unrolls a list, one row per index it draws. */
  fun string(list: ContractList, index: Int, field: ContractInput): RemoteString = string(list.valueName(index, field).also { own(list) })

  fun int(list: ContractList, index: Int, field: ContractInput): RemoteInt = int(list.valueName(index, field).also { own(list) })

  fun bool(list: ContractList, index: Int, field: ContractInput): RemoteBoolean = bool(list.valueName(index, field).also { own(list) })

  /**
   * The action [action] with its one string argument set to [value] as the document plays (a URL
   * the template reads from an input): the bare text for an argument named `value`, else the JSON
   * object `{"<arg>":"<value>"}` built in the document. [value] is not escaped, so it must not hold
   * a `"` or `\\`.
   */
  fun action(action: ContractAction, value: RemoteString): RemoteAction {
    require(action.contract.ref == contract.ref) { "$action is not of ${contract.ref}" }
    val arg = action.spec.args.keys.singleOrNull()
    require(arg != null && action.spec.args.getValue(arg).type == ValueType.String) { "$action takes one string argument, not ${action.spec.args}" }
    val payload = if (arg == "value") value else "{\"$arg\":\"".rs + value + "\"}".rs
    return hostAction(action.name.rs, payload)
  }

  /** The action [action], with [args] by name, as [action] (by name) sends it. */
  @Composable
  fun action(action: ContractAction, args: Map<String, String> = emptyMap()): RemoteAction {
    require(action.contract.ref == contract.ref) { "$action is not of ${contract.ref}" }
    return action(action.name, args)
  }

  fun string(name: String): RemoteString = RemoteString.createNamedRemoteString(name, checks.string(name), USER)

  fun int(name: String): RemoteInt = RemoteInt.createNamedRemoteInt(name, checks.int(name), USER)

  fun long(name: String): RemoteLong = RemoteLong.createNamedRemoteLong(name, checks.long(name), USER)

  fun float(name: String): RemoteFloat = RemoteFloat.createNamedRemoteFloat(name, checks.float(name), USER)

  fun bool(name: String): RemoteBoolean = RemoteBoolean.createNamedRemoteBoolean(name, checks.bool(name), USER)

  /** An image input, drawn from `livery:value/<name>` (PROTOCOL.md §6.1): the client resolves it to the input's value. */
  @Composable
  fun image(name: String): RemoteImageBitmap = rememberRemoteImageBitmap(checks.image(name))

  @Composable
  fun image(input: ContractInput): RemoteImageBitmap = image(own(input))

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
