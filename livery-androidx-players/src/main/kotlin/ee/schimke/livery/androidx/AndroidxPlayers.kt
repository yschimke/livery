package ee.schimke.livery.androidx

import androidx.compose.remote.player.compose.ExperimentalRemotePlayerApi
import androidx.compose.remote.player.compose.RemoteComposePlayerFlags
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.remote.player.view.RemoteComposePlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.viewinterop.AndroidView
import ee.schimke.composeai.rcplayer.compose.RcDisplay
import ee.schimke.composeai.rcplayer.compose.RcPlayerTheme
import ee.schimke.composeai.rcplayer.runtime.RcHostActionValue
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.livery.client.LoadedTemplate
import ee.schimke.livery.compose.LiveryPlayer
import ee.schimke.livery.compose.PlayerRequest
import ee.schimke.livery.compose.TemplateAction
import ee.schimke.livery.compose.templateAction
import java.util.concurrent.ConcurrentHashMap

/**
 * AndroidX's own players for Livery. Neither takes rc-players' prepared document, `forDisplay` rewrite,
 * deferred layout animations or flat layout: each card parses the template's bytes itself, as AndroidX does.
 * [prepare] parses a template once off the main thread, to find out whether it can be played and to load
 * the player's classes; a parsed document carries a card's state and is not shared between cards.
 *
 * Not drawn through these yet: images bound to data (`livery:value/...`) and Livery's catalog fonts (the
 * platform's typefaces are used). See docs/PLAYERS.md.
 */
@OptIn(ExperimentalRemotePlayerApi::class)
object LiveryAndroidxPlayers {
  /** The View player (`RemoteComposePlayer`) in an `AndroidView`. */
  val View: LiveryPlayer = ViewPlayer()

  /** The embedded player (`RcPlayer`, pure Compose). */
  val Embedded: LiveryPlayer by lazy {
    // AndroidX ships the embedded player switched off.
    RemoteComposePlayerFlags.isEmbeddedPlayerEnabled = true
    EmbeddedPlayer()
  }
}

internal abstract class ParsingPlayerBase : LiveryPlayer {
  private val parsed = ConcurrentHashMap.newKeySet<String>()

  override val usesPreparedDocument: Boolean get() = false

  override suspend fun prepare(template: LoadedTemplate, display: RcDisplay?) {
    if (template.entry.sha256 in parsed) return
    RemoteDocument(template.bytes.toByteArray())
    parsed.add(template.entry.sha256)
  }

  override fun isPrepared(template: LoadedTemplate): Boolean = template.entry.sha256 in parsed
}

private class ViewPlayer : ParsingPlayerBase() {
  override val id: String = "androidx-view"

  @Composable
  override fun Play(request: PlayerRequest) {
    val onAction = rememberUpdatedState(request.onAction)
    val values = request.values
    AndroidView(
      factory = { context ->
        RemoteComposePlayer(context).apply {
          setTheme(request.theme.toViewTheme())
          setDocument(request.template.bytes.toByteArray())
          document.document.addActionCallback { name, value -> hostAction(name, value, request)?.let(onAction.value) }
          // Values in place before the first frame.
          values.forEach { (name, v) -> push(name, v) }
        }
      },
      modifier = request.modifier,
      update = { player -> values.forEach { (name, v) -> player.push(name, v) } },
    )
  }

  private fun RemoteComposePlayer.push(name: String, v: RcNamedValue) {
    when (v) {
      is RcNamedValue.Text -> setUserLocalString(name, v.value)
      is RcNamedValue.Integer -> setUserLocalInt(name, v.value)
      is RcNamedValue.FloatValue -> setUserLocalFloat(name, v.value)
      is RcNamedValue.Color -> setUserLocalColor(name, v.argb)
      is RcNamedValue.BooleanValue -> setUserLocalInt(name, if (v.value) 1 else 0)
      else -> Unit
    }
  }

  private fun RcPlayerTheme.toViewTheme(): Int = when (this) {
    RcPlayerTheme.Light -> RemoteComposePlayer.THEME_LIGHT
    RcPlayerTheme.Dark -> RemoteComposePlayer.THEME_DARK
    else -> RemoteComposePlayer.THEME_UNSPECIFIED
  }
}

/** Livery's action convention, over the (name, value) a player reports. */
internal fun hostAction(name: String, value: Any?, request: PlayerRequest): TemplateAction? =
  templateAction(name, value.toHostActionValue(), request.template.contract)

private fun Any?.toHostActionValue(): RcHostActionValue = when (this) {
  is String -> RcHostActionValue.TextValue(this)
  is Int -> RcHostActionValue.IntegerValue(this)
  is Float -> RcHostActionValue.FloatValue(this)
  else -> RcHostActionValue.None
}
