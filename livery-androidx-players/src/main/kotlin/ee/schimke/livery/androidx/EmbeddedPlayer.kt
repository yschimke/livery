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
    val onAction = rememberUpdatedState(request.onAction)
    val state = remember(request.template.entry.sha256) { RcPlayerState(RemoteDocument(request.template.bytes.toByteArray()).document, "USER") }
    // Seeded and pushed: a write to a state the document reads recomposes only what reads it.
    SideEffect {
      request.values.forEach { (name, v) ->
        when (v) {
          is RcNamedValue.Text -> state.stringState(name, "USER").value = v.value
          is RcNamedValue.Integer -> state.intState(name, "USER").value = v.value
          is RcNamedValue.FloatValue -> state.floatState(name, "USER").value = v.value
          is RcNamedValue.Color -> state.colorState(name, "USER").value = Color(v.argb)
          is RcNamedValue.BooleanValue -> state.booleanState(name, "USER").value = v.value
          else -> Unit
        }
      }
    }
    RcPlayer(
      state = state,
      modifier = request.modifier,
      onNamedAction = { name, value, _ -> hostAction(name, value, request)?.let(onAction.value) },
    )
  }
}
