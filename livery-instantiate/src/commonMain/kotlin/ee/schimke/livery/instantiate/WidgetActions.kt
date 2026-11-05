package ee.schimke.livery.instantiate

import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcHostAction
import ee.schimke.composeai.rcplayer.protocol.RcHostMetadataAction
import ee.schimke.composeai.rcplayer.protocol.RcHostNamedAction
import ee.schimke.composeai.rcplayer.protocol.RcHostNamedActionValue
import ee.schimke.composeai.rcplayer.protocol.RcIntegerConstant
import ee.schimke.composeai.rcplayer.protocol.RcOperation
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcwidget.RcHostActionRewrites
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

/**
 * A template's taps for a Glance Wear widget host, which runs one host action only:
 * `SendPendingIntent`, whose integer value indexes the `PendingIntent`s in the widget's extras.
 *
 * Each distinct tap (contract action and constant arguments) gets its own index, so a list's rows
 * keep their own taps; the host app builds one `PendingIntent` per entry of [Rewritten.taps]. A tap
 * whose arguments are only known when tapped (an expression) can't be carried by a
 * `PendingIntent`, which is fixed when the widget is published: it is left as it was, which the
 * host ignores, and counted in [Rewritten.dropped].
 *
 * Like [LauncherActions], nothing here is Livery-specific beyond the action name; it generalises
 * rc-widget-core's `RcHostActionRewrites.toPendingIntent` from one index to one per tap.
 */
object WearActions {
  /** The action name a Wear widget host runs: AndroidX's `PendingIntentAction.ACTION_NAME`. */
  const val SEND_PENDING_INTENT: String = RcHostActionRewrites.SEND_PENDING_INTENT

  /** [document] with its taps sending `PendingIntent`s; index `i` stands for `taps[i]`. */
  data class Rewritten(val document: RcDocument, val taps: List<WidgetTap>, val dropped: Int)

  fun rewrite(document: RcDocument): Rewritten {
    val texts = document.operations.filterIsInstance<RcTextData>().associate { it.id to it.text }
    // Each rewritable action op with its tap; others stay.
    val tapOf = HashMap<Int, WidgetTap>()
    var dropped = 0
    document.operations.forEachIndexed { i, op ->
      if (op !is RcHostNamedAction) return@forEachIndexed
      val name = texts[op.nameTextId] ?: return@forEachIndexed
      val args = when (val v = op.value) {
        is RcHostNamedActionValue.TextValue -> texts[v.valueId]?.let(ActionArguments::parse)
        else -> null
      }
      if (args == null && op.value !is RcHostNamedActionValue.None) { dropped++; return@forEachIndexed }
      tapOf[i] = WidgetTap(name, args ?: emptyMap())
    }
    if (tapOf.isEmpty()) return Rewritten(document, emptyList(), dropped)
    val taps = tapOf.values.distinct()
    val fresh = freshIds(document, taps.size + 1)
    val nameId = fresh[0]
    val first = tapOf.keys.min()
    val operations = ArrayList<RcOperation>(document.operations.size + fresh.size)
    document.operations.forEachIndexed { i, op ->
      // Declared before the first action that reads them.
      if (i == first) {
        operations += RcTextData(nameId, SEND_PENDING_INTENT)
        taps.forEachIndexed { index, _ -> operations += RcIntegerConstant(fresh[index + 1], index) }
      }
      val tap = tapOf[i]
      operations += if (tap == null) op else RcHostNamedAction(nameId, RcHostNamedActionValue.IntegerValue(fresh[taps.indexOf(tap) + 1]))
    }
    return Rewritten(document.copy(operations = operations), taps, dropped)
  }

  /**
   * [count] ids [document] doesn't use, for the operations a rewrite adds: every 4-byte window of
   * its encoding counts as used, which over-counts but never misses one (rc-widget-core's own rule).
   */
  internal fun freshIds(document: RcDocument, count: Int): List<Int> {
    val bytes = RcDocumentCodec.encode(document)
    val used = HashSet<Int>()
    for (i in 0..bytes.size - 4) {
      val word = (bytes[i].toInt() and 0xff shl 24) or (bytes[i + 1].toInt() and 0xff shl 16) or
        (bytes[i + 2].toInt() and 0xff shl 8) or (bytes[i + 3].toInt() and 0xff)
      used += word
      used += word and 0x003fffff
    }
    return generateSequence(42) { it + 1 }.filter { it !in used }.take(count).toList()
  }
}
