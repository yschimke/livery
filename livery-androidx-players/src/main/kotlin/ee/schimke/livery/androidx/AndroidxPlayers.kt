package ee.schimke.livery.androidx

import androidx.compose.remote.player.compose.ExperimentalRemotePlayerApi
import androidx.compose.remote.player.compose.RemoteComposePlayerFlags
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.remote.player.view.RemoteComposePlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.fillMaxWidth
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
import ee.schimke.composeai.rcplayer.edit.forDisplay
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import java.util.concurrent.ConcurrentHashMap

/**
 * AndroidX's own players for Livery. Neither takes rc-players' prepared document, `forDisplay` rewrite,
 * deferred layout animations or flat layout: each card parses the template's bytes itself, as AndroidX does.
 * [prepare] parses a template once off the main thread, to find out whether it can be played and to load
 * the player's classes; a parsed document carries a card's state and is not shared between cards.
 *
 * **The View player draws nothing for Livery's templates in `1.0.0-alpha23`** (docs/benchmarks/PLAYERS.md): it is here so
 * that changes, and a fixed player, show up in the lanes. Not drawn through these yet: images bound to data (`livery:value/...`) and Livery's catalog fonts (the
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
  private val rewritten = ConcurrentHashMap<String, ByteArray>()

  override val usesPreparedDocument: Boolean get() = false

  /** Whether the player is handed the document rewritten for the display (density hardcoded, constants folded). */
  protected open val rewritesForDisplay: Boolean get() = false

  override suspend fun prepare(template: LoadedTemplate, display: RcDisplay?) {
    val sha = template.entry.sha256
    if (sha in parsed) return
    var bytes = template.bytes.toByteArray()
    if (rewritesForDisplay && display != null) {
      bytes = RcDocumentCodec.encode(RcDocumentCodec.decode(bytes, RcWireLimits()).forDisplay(display.density, display.fontScale))
      rewritten[sha] = bytes
    }
    RemoteDocument(bytes)
    parsed.add(sha)
  }

  override fun isPrepared(template: LoadedTemplate): Boolean = template.entry.sha256 in parsed

  /** The bytes this player parses for [template]: as published, or rewritten for the display when [prepare] did that. */
  protected fun bytesFor(template: LoadedTemplate): ByteArray = (rewritten[template.entry.sha256] ?: template.bytes.toByteArray())
}

private class ViewPlayer : ParsingPlayerBase() {
  override val id: String = "androidx-view"

  // The View player does not evaluate density expressions: it is handed literal values.
  override val rewritesForDisplay: Boolean get() = true

  @Composable
  override fun Play(request: PlayerRequest) {
    // The latest request, for the callback the view keeps; the view itself is per template, so another design or
    // another version of the bytes gets a new document and a new callback, not the old ones.
    val current = rememberUpdatedState(request)
    val values = request.values
    key(request.template.entry.sha256) {
    val written = remember { mutableMapOf<String, RcNamedValue>() }
    AndroidView(
      factory = { context ->
        RemoteComposePlayer(context).apply {
          setTheme(request.theme.toViewTheme())
          setDocument(bytesFor(request.template))
          document.document.addActionCallback { name, value -> hostAction(name, value, current.value)?.let(current.value.onAction) }
          // Values in place before the first frame.
          pushAll(values, written)
        }
      },
      modifier = request.modifier.fillMaxWidth(),
      update = { player -> player.pushAll(values, written) },
    )
    }
  }

  /** Pushes [values], and clears what an earlier push set that is no longer bound, so it returns to the document's value. */
  private fun RemoteComposePlayer.pushAll(values: Map<String, RcNamedValue>, written: MutableMap<String, RcNamedValue>) {
    (written.keys - values.keys).forEach { name ->
      when (written.remove(name)) {
        is RcNamedValue.Text -> clearUserLocalString(name)
        is RcNamedValue.Integer, is RcNamedValue.BooleanValue -> clearUserLocalInt(name)
        is RcNamedValue.FloatValue -> clearUserLocalFloat(name)
        is RcNamedValue.Color -> clearUserLocalColor(name)
        else -> Unit
      }
    }
    values.forEach { (name, v) -> push(name, v); written[name] = v }
  }

  private fun RemoteComposePlayer.push(name: String, v: RcNamedValue) {
    when (v) {
      is RcNamedValue.Text -> setUserLocalString(name, v.value)
      is RcNamedValue.Integer -> setUserLocalInt(name, v.value)
      is RcNamedValue.FloatValue -> setUserLocalFloat(name, v.value)
      is RcNamedValue.Color -> setUserLocalColor(name, v.argb)
      is RcNamedValue.BooleanValue -> setUserLocalInt(name, if (v.value) 1 else 0)
      is RcNamedValue.LongValue -> stateUpdater.setNamedLong(name, v.value)
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
