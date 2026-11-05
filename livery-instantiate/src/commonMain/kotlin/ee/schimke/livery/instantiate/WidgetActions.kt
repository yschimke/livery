package ee.schimke.livery.instantiate

import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcHostAction
import ee.schimke.composeai.rcplayer.protocol.RcHostMetadataAction
import ee.schimke.composeai.rcplayer.protocol.RcHostNamedAction
import ee.schimke.composeai.rcplayer.protocol.RcHostNamedActionValue
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcwidget.RcWidgetActionIds
import ee.schimke.livery.client.ActionArguments

/** A template tap as a widget host delivers it: the contract action, and its arguments. */
data class WidgetTap(val action: String, val args: Map<String, String>)

/**
 * A template's taps for hosts that dispatch numeric host actions only (a launcher widget's
 * `RemoteViews` player): each named host action becomes the numeric action whose id
 * [RcWidgetActionIds] gives its contract action name, as rc-players' own `rcWidgetHostAction` does
 * for a captured widget. A text value (PROTOCOL.md §7 arguments, possibly an expression) becomes
 * the action's metadata, which the player evaluates when tapped and delivers with the tap; other
 * values are dropped, since such a host can carry only text.
 *
 * Kept free of anything Livery-specific beyond the action name, so it can move into rc-players'
 * `rc-widget-core` beside `RcHostActionRewrites.toPendingIntent`.
 */
object LauncherActions {
  /** [document] with numeric taps, and each tap's id with its action name. */
  data class Rewritten(val document: RcDocument, val actions: Map<Int, String>)

  fun rewrite(document: RcDocument): Rewritten {
    val texts = document.operations.filterIsInstance<RcTextData>().associate { it.id to it.text }
    val ids = RcWidgetActionIds()
    val operations = document.operations.map { op ->
      val name = (op as? RcHostNamedAction)?.let { texts[it.nameTextId] } ?: return@map op
      val id = ids.register(name)
      when (val v = (op as RcHostNamedAction).value) {
        is RcHostNamedActionValue.TextValue -> RcHostMetadataAction(id, v.valueId)
        else -> RcHostAction(id)
      }
    }
    return Rewritten(document.copy(operations = operations), ids.entries)
  }

  /**
   * The tap a launcher delivered (`RcLauncherWidgets.tapOf`: its action id and metadata text), or
   * null for an id the template didn't declare or metadata that isn't Livery arguments.
   */
  fun tap(actions: Map<Int, String>, actionId: Int, metadata: String?): WidgetTap? {
    val action = actions[actionId] ?: return null
    val args = ActionArguments.parse(metadata) ?: return null
    return WidgetTap(action, args)
  }
}
