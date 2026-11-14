package ee.schimke.livery.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import ee.schimke.composeai.rcplayer.compose.ExperimentalRcPlayerApi
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.compose.RcDisplay
import ee.schimke.composeai.rcplayer.compose.RcImageLoader
import ee.schimke.composeai.rcplayer.compose.RcPlayerTheme
import ee.schimke.composeai.rcplayer.compose.RcPreparedDocument
import ee.schimke.composeai.rcplayer.compose.RcTypefaceLoader
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.composeai.rcplayer.runtime.RcPlayerEvent
import ee.schimke.livery.client.Bindings
import ee.schimke.livery.client.LoadedTemplate

/**
 * What a card hands its player: the document, the values to show and everything Livery owns around
 * it (visibility, events, actions and the first-frame rule stay in [LiveryTemplate]).
 */
class PlayerRequest(
  val template: LoadedTemplate,
  /** The document prepared for the display, for a player whose [LiveryPlayer.usesPreparedDocument]; null for any other. */
  val prepared: RcPreparedDocument?,
  /** The values to show, in place before the first frame; later changes are pushed here without restarting the document. */
  val values: SnapshotStateMap<String, RcNamedValue>,
  /** The card's current bindings, for a player that resolves a data-bound image itself. */
  val bindings: () -> Bindings,
  val imageLoader: RcImageLoader,
  val typefaces: RcTypefaceLoader,
  val theme: RcPlayerTheme,
  /** A named host action the document emitted: Livery's convention, dispatched through the host's router. */
  val onAction: (TemplateAction) -> Unit,
  val modifier: Modifier,
)

/**
 * Plays a template's document. [LiveryHost] plays with [LiveryPlayer.Cmp] unless it is given another
 * (an Android app with the `livery-androidx-players` module can pass the AndroidX View or embedded
 * player). Visibility, impressions, actions, fonts and the first-frame rules stay Livery's.
 */
interface LiveryPlayer {
  val id: String

  /**
   * Whether the player draws the [RcPreparedDocument] Livery prepares (rc-players' Compose player). A
   * player that parses the document bytes itself returns false, and Livery does not prepare one for it:
   * it [prepare]s its own.
   */
  val usesPreparedDocument: Boolean get() = true

  /** Gets what [template] needs in memory, off the main thread, before a card of it composes. */
  suspend fun prepare(template: LoadedTemplate, display: RcDisplay?) {}

  /** Whether [prepare] has finished for [template]: a card draws only once it has. */
  fun isPrepared(template: LoadedTemplate): Boolean = true

  @Composable fun Play(request: PlayerRequest)

  /** rc-players' Compose Multiplatform player: the default, on every target. */
  object Cmp : LiveryPlayer {
    override val id: String = "cmp"

    @OptIn(ExperimentalRcPlayerApi::class)
    @Composable
    override fun Play(request: PlayerRequest) {
      RcComposePlayer(
        prepared = checkNotNull(request.prepared) { "the Compose player draws a prepared document" },
        imageLoader = request.imageLoader,
        modifier = request.modifier,
        theme = request.theme,
        namedValues = request.values,
        onEvent = { e: RcPlayerEvent -> e.toTemplateAction(request.template.contract)?.let(request.onAction) },
        typefaces = request.typefaces,
      )
    }
  }
}
