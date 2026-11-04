package ee.schimke.livery.compose

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import java.io.File

internal actual fun decodeImage(bytes: ByteArray): ImageBitmap =
  checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "not an image" }.asImageBitmap()

/** Android loads fonts from files: written once per hash to the app's cache (`java.io.tmpdir`). */
internal actual fun fontFrom(sha256: String, bytes: ByteArray, weight: FontWeight, style: FontStyle): Font {
  val file = File(System.getProperty("java.io.tmpdir"), "livery-font-$sha256")
  if (!file.isFile || file.length() != bytes.size.toLong()) file.writeBytes(bytes)
  return Font(file, weight, style)
}
