package ee.schimke.livery.androidx

import androidx.compose.remote.player.compose.embedded.RcPlayer
import androidx.compose.remote.player.compose.embedded.RcPlayerState
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.livery.compose.PlayerRequest

internal class EmbeddedPlayer : ParsingPlayerBase() {
  override val id: String = "androidx-embedded"

  @Composable
  override fun Play(request: PlayerRequest) {
    val current = rememberUpdatedState(request)
    // The card's values are in the state before the player composes it, so the first frame shows them, not the template's defaults.
    val written = remember(request.template.entry.sha256) { mutableMapOf<String, RcNamedValue>() }
    val state = remember(request.template.entry.sha256) {
      RcPlayerState(RemoteDocument(request.template.bytes.toByteArray()).document, "USER").also { it.seed(request.values, written) }
    }
    // Later changes: a write to a state the document reads recomposes only what reads it.
    SideEffect { state.seed(request.values, written) }
    RcPlayer(
      state = state,
      modifier = request.modifier,
      onNamedAction = { name, value, _ -> hostAction(name, value, current.value)?.let(current.value.onAction) },
    )
  }

  /**
   * Writes [values] and clears what an earlier call wrote that is no longer there (an optional input the data stopped
   * binding goes back to the document's own value). A long has no state in the embedded player, so it is not shown.
   */
  private fun RcPlayerState.seed(values: Map<String, RcNamedValue>, written: MutableMap<String, RcNamedValue>) {
    (written.keys - values.keys).forEach { clearOverride(it, "USER"); written.remove(it) }
    values.forEach { (name, v) -> if (v !is RcNamedValue.LongValue) written[name] = v }
    values.forEach { (name, v) ->
      when (v) {
        is RcNamedValue.Text -> stringState(name, "USER").value = v.value
        is RcNamedValue.Integer -> intState(name, "USER").value = v.value
        is RcNamedValue.FloatValue -> floatState(name, "USER").value = v.value
        is RcNamedValue.Color -> colorState(name, "USER").value = Color(v.argb)
        is RcNamedValue.BooleanValue -> booleanState(name, "USER").value = v.value
        else -> Unit
      }
    }
  }
}
