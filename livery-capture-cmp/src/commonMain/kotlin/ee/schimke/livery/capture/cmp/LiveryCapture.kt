package ee.schimke.livery.capture.cmp

import androidx.compose.runtime.Composable
import ee.schimke.composeai.rccreation.compose.capture.RemoteCreationDisplayInfo
import ee.schimke.composeai.rccreation.compose.capture.RemoteDensity
import ee.schimke.composeai.rccreation.compose.capture.captureCommonRemoteDocument
import ee.schimke.composeai.rcwidget.RcWidgetTargets
import ee.schimke.livery.capture.CapturedTemplate
import ee.schimke.livery.protocol.Target

/**
 * Captures a `@RemoteComposable` written against rc-players' Compose Multiplatform creation API
 * (`ee.schimke.composeai.rccreation`) for a [Target]: no `Context`, no Robolectric, so from a plain
 * JVM test, a build tool, a server or a browser. The sidecar's reads and emits come from the
 * captured document itself, so they can't drift from what the template does.
 *
 * Content composed with AndroidX's own `remote-creation-compose` (and the component libraries built
 * on it) is captured by `livery-capture`'s `ee.schimke.livery.capture.LiveryCapture` instead.
 */
object LiveryCapture {
  /**
   * Captures [content] at [widthDp] × [heightDp] (a size inside [target]'s range) for [target].
   * [source] goes into the sidecar (`tool` is `capture` unless it names another).
   *
   * A target for a widget player is held to that player, and a capture it would refuse fails here,
   * with what it refuses, and is never returned:
   * - `widgets` (a launcher widget or a notification): the document is written in what Android 16's
   *   level 6 player and the level 7 one both decode (text as `TextLayout`, the widgets profile in
   *   its header), then checked on each after the recovery `rc-widget-launcher` applies before it
   *   builds `RemoteViews`. `livery-verify` plays such a capture on Android 16's own player.
   * - `glance-wear` (a Glance Wear widget): checked against the Wear widget allow-list after the
   *   adaptation `livery-wear-widgets` instantiates with. It is checked, not played: nothing here
   *   runs Glance Wear's player.
   */
  suspend fun capture(
    target: Target,
    widthDp: Int,
    heightDp: Int,
    source: Map<String, String> = emptyMap(),
    content: @Composable () -> Unit,
  ): CapturedTemplate {
    val widget = WidgetCaptureTargets.of(target)
    val captured = captureCommonRemoteDocument(
      creationDisplayInfo = RemoteCreationDisplayInfo(widthDp, heightDp, 160),
      remoteDensity = RemoteDensity.Host,
      // What a component that asks is told the reader takes: a widget player's own set, or an
      // app's player, which reads everything this API writes.
      supportedOperations = widget?.operations ?: APP_OPERATIONS,
      content = content,
    )
    val bytes = widget?.forPlayer(captured) ?: captured
    widget?.problems(bytes)?.takeIf { it.isNotEmpty() }?.let { problems ->
      throw IllegalStateException("the capture wouldn't play as ${target.profiles.joinToString("+")} on ${target.surface}: ${problems.joinToString("; ")}")
    }
    return CapturedTemplate.of(bytes, target, source)
  }

  /** The AndroidX embedded player's operations with its experimental ones: what the Compose player reads too. */
  private val APP_OPERATIONS: Set<Int> = RcWidgetTargets.App.profile.supportedOpcodes
}
