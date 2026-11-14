package ee.schimke.livery.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font as PlatformFont
import ee.schimke.composeai.rcplayer.compose.RcDisplay
import org.jetbrains.skia.Image

internal actual fun decodeImage(bytes: ByteArray): ImageBitmap = Image.makeFromEncoded(bytes).toComposeImageBitmap()

internal actual fun fontFrom(sha256: String, bytes: ByteArray, weight: FontWeight, style: FontStyle): Font =
  PlatformFont(sha256, bytes, weight, style)

/** No display is known before a window composes; the first slot's composition records it. */
internal actual fun platformDisplay(): RcDisplay? = null

/** Nothing to defer on desktop, iOS or the web. */
@androidx.compose.runtime.Composable internal actual fun AfterFirstTemplateFrame() = Unit
