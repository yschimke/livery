package ee.schimke.livery.compose

import okio.sink
import okio.buffer
import okio.ByteString
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import ee.schimke.composeai.rcplayer.compose.RcDisplay
import java.io.File

internal actual fun decodeImage(bytes: ByteString): ImageBitmap {
  val array = bytes.toByteArray()
  return checkNotNull(BitmapFactory.decodeByteArray(array, 0, array.size)) { "not an image" }.asImageBitmap()
}

/** Android loads fonts from files: written once per hash to the app's cache (`java.io.tmpdir`). */
internal actual fun fontFrom(sha256: String, bytes: ByteString, weight: FontWeight, style: FontStyle): Font {
  val file = File(System.getProperty("java.io.tmpdir"), "livery-font-$sha256")
  if (!file.isFile || file.length() != bytes.size.toLong()) file.sink().buffer().use { it.write(bytes) }
  return Font(file, weight, style)
}

/**
 * The device's display, before anything has composed: what an app's first composition reads as
 * `LocalDensity` unless it overrides the density, so work prepared ahead of it (a splash screen's
 * `startup`) is the work the first slot looks up.
 */
internal actual fun platformDisplay(): RcDisplay? {
  val resources = android.content.res.Resources.getSystem()
  return RcDisplay(resources.displayMetrics.density, resources.configuration.fontScale)
}
