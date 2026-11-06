package ee.schimke.livery.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ee.schimke.livery.client.Bindings
import ee.schimke.livery.client.LiveryScreens
import ee.schimke.livery.client.PresentedScreen
import ee.schimke.livery.protocol.NamedValue
import ee.schimke.livery.protocol.Presentation
import ee.schimke.livery.protocol.ScreenValues

/**
 * Draws [screens]' secondary screens (PROTOCOL.md §8.2) over the app, each in its own window:
 *
 * - `dialog`: centred, at the template's own size, over a scrim;
 * - `sheet`: across the bottom, full width, over a scrim a tap on which closes it;
 * - `fullscreen`: the whole window, with the safe-area insets pushed as `livery.inset.*` (dp) so
 *   the template lays out around cut-outs and system bars.
 *
 * Back (and, for a dialog, a tap outside) closes the top screen with reason `system`; the
 * template's own `dismiss` closes it with `action` (route actions with [LiveryScreens.handlers]).
 * Put it once, at the root of the app's UI, beside [host]'s slots.
 */
@Composable
fun LiveryScreenHost(screens: LiveryScreens, host: LiveryHost) {
  val stack by screens.stack.collectAsState()
  for (s in stack) key(s.id) { LiveryScreen(s, screens, host) }
}

@Composable
private fun LiveryScreen(s: PresentedScreen, screens: LiveryScreens, host: LiveryHost) {
  val close = { screens.dismiss(s, "system") }
  val full = s.presentation != Presentation.Dialog
  Dialog(
    onDismissRequest = close,
    // The parameters every platform's DialogProperties has. Where the platform keeps a dialog
    // inside the system bars, the insets read 0 and the template still clears them.
    properties = DialogProperties(
      dismissOnBackPress = true,
      dismissOnClickOutside = s.presentation == Presentation.Dialog,
      usePlatformDefaultWidth = !full,
    ),
  ) {
    when (s.presentation) {
      Presentation.Fullscreen -> Box(Modifier.fillMaxSize()) {
        LiveryTemplate(s.plan, host, Modifier.fillMaxSize(), extra = insets())
      }
      Presentation.Sheet -> Box(
        Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = close),
        contentAlignment = Alignment.BottomCenter,
      ) {
        // The sheet itself eats taps, so only the scrim around it closes it.
        Box(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null) {}) {
          LiveryTemplate(s.plan, host, Modifier.fillMaxWidth(), extra = insets(top = false))
        }
      }
      else -> LiveryTemplate(s.plan, host)
    }
  }
}

/** The safe-area insets in dp, as a screen's host values. */
@Composable
private fun insets(top: Boolean = true): Bindings {
  val density = LocalDensity.current
  val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
  val i = WindowInsets.safeDrawing
  fun dp(px: Int) = NamedValue.F(px / density.density)
  val left = dp(i.getLeft(density, LocalLayoutDirection.current))
  val right = dp(i.getRight(density, LocalLayoutDirection.current))
  return Bindings(
    mapOf(
      ScreenValues.INSET_TOP to if (top) dp(i.getTop(density)) else NamedValue.F(0f),
      ScreenValues.INSET_BOTTOM to dp(i.getBottom(density)),
      ScreenValues.INSET_START to if (rtl) right else left,
      ScreenValues.INSET_END to if (rtl) left else right,
    ),
  )
}
