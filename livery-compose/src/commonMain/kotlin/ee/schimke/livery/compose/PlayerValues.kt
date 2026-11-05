package ee.schimke.livery.compose

import ee.schimke.composeai.rcplayer.runtime.RcHostActionValue
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.composeai.rcplayer.runtime.RcPlayerEvent
import ee.schimke.livery.client.ActionArguments
import ee.schimke.livery.client.Bindings
import ee.schimke.livery.protocol.NamedValue

/**
 * The named values the player is handed. Images are not named values in Remote Compose: a
 * document draws a data-bound image from the URL `livery:value/<input>`, which [LiveryImageLoader]
 * resolves from these same bindings.
 */
fun Bindings.toPlayerValues(): Map<String, RcNamedValue> = buildMap {
  for ((name, v) in this@toPlayerValues.values) {
    when (v) {
      is NamedValue.Str -> put(name, RcNamedValue.Text(v.value))
      is NamedValue.I -> put(name, RcNamedValue.Integer(v.value))
      is NamedValue.L -> put(name, RcNamedValue.LongValue(v.value))
      is NamedValue.F -> put(name, RcNamedValue.FloatValue(v.value))
      is NamedValue.B -> put(name, RcNamedValue.BooleanValue(v.value))
      is NamedValue.Color -> put(name, RcNamedValue.Color(v.argb.toInt()))
      is NamedValue.Image -> Unit
    }
  }
}

/** A host action a template emitted: its contract action name and flat string arguments. */
data class TemplateAction(val name: String, val args: Map<String, String>)

/**
 * Livery's action convention: a template emits a host named action whose name is the contract
 * action and whose value is either nothing, a JSON object of string arguments, or a single value
 * (passed as `value`). Anything else (numeric host action ids, malformed JSON) is not an action.
 */
fun RcPlayerEvent.toTemplateAction(): TemplateAction? {
  val e = this as? RcPlayerEvent.HostNamedAction ?: return null
  val args: Map<String, String> = when (val v = e.value) {
    is RcHostActionValue.TextValue -> parseArgs(v.value) ?: return null
    is RcHostActionValue.IntegerValue -> mapOf("value" to v.value.toString())
    is RcHostActionValue.FloatValue -> mapOf("value" to v.value.toString())
    else -> emptyMap()
  }
  return TemplateAction(e.name, args)
}

private fun parseArgs(text: String): Map<String, String>? = ActionArguments.parse(text)
