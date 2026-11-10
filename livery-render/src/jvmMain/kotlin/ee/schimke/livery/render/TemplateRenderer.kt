package ee.schimke.livery.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.compose.RcImageLoader
import ee.schimke.composeai.rcplayer.compose.RcPlayerTheme
import ee.schimke.composeai.rcplayer.compose.RcTypefaceLoader
import ee.schimke.livery.client.Bindings
import ee.schimke.livery.compose.toPlayerValues
import org.jetbrains.skia.EncodedImageFormat

/** How to render: the size in dp, the screen density, and the player's theme. */
data class RenderSpec(
  val widthDp: Int,
  val heightDp: Int,
  val density: Float = 2f,
  val dark: Boolean = false,
  /**
   * ARGB drawn behind the template, or null for none (transparent). A template draws over whatever
   * its host puts behind it, so a preview of one without a background of its own needs the
   * surface it will sit on: [CatalogPreviews] passes the theme's `theme.surface`.
   */
  val background: Long? = null,
) {
  init { require(widthDp in 1..4096 && heightDp in 1..4096 && density in 0.5f..4f) { "render size out of range" } }
  val widthPx: Int get() = (widthDp * density).toInt()
  val heightPx: Int get() = (heightDp * density).toInt()
}

/**
 * Renders a template with its values to a PNG, headless, as the Compose player draws it on a
 * device: the values are in place before the first frame, so the image is what a person sees.
 *
 * For an agent's `render_preview`, a CI preview step or generating a picker's previews. Images
 * come from [images] (by default none, so a template that loads a URL draws its placeholder); write
 * them in first with `livery-instantiate` for an exact widget render.
 */
object TemplateRenderer {
  fun png(
    template: ByteArray,
    bindings: Bindings,
    spec: RenderSpec,
    images: RcImageLoader = RcImageLoader.Default,
    typefaces: RcTypefaceLoader = RcTypefaceLoader.Default,
  ): ByteArray {
    val values = mutableStateMapOf<String, ee.schimke.composeai.rcplayer.runtime.RcNamedValue>().apply { putAll(bindings.toPlayerValues()) }
    val scene = ImageComposeScene(spec.widthPx, spec.heightPx, Density(spec.density)) {
      RcComposePlayer(
        bytes = template,
        imageLoader = images,
        modifier = Modifier.fillMaxSize().let { m -> spec.background?.let { m.background(Color(it.toInt())) } ?: m },
        theme = if (spec.dark) RcPlayerTheme.Dark else RcPlayerTheme.Light,
        namedValues = values,
        onEvent = {},
        typefaces = typefaces,
      )
    }
    try {
      // The first frame lays out and decodes; the second draws anything that arrived in it.
      scene.render(0)
      val image = scene.render(FRAME_NANOS)
      return image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("PNG encoding failed")
    } finally {
      scene.close()
    }
  }

  private const val FRAME_NANOS = 16_000_000L
}
