@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package ee.schimke.livery.web.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.LocalComposeWindow
import androidx.compose.ui.platform.FallbackFontDownloader
import androidx.compose.ui.platform.defaultFallbackFontDownloader
import androidx.compose.ui.text.font.FontFamily

/** Compose normally downloads missing glyphs during draw. This renderer is deliberately offline. */
internal fun disableFontDownloads() {
  defaultFallbackFontDownloader = object : FallbackFontDownloader {
    override suspend fun downloadFallbackFont(codepoints: Set<Int>): List<FontFamily> = emptyList()
  }
}

/**
 * Compose 1.12.2's public ComposeViewport returns Unit. Keep its internal lifecycle dependency
 * here until it exposes a public close handle. Removing DOM alone leaves the recomposer, Skia
 * layer and window listeners alive. Recheck this bridge on Compose upgrades.
 */
@Composable
internal fun viewportDisposer(): () -> Unit {
  val window = checkNotNull(LocalComposeWindow.current)
  return { window.dispose() }
}
