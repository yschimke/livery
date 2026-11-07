package ee.schimke.livery.review

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import ee.schimke.composeai.rcplayer.compose.RcTypefaceLoader
import ee.schimke.livery.compose.LiveryTypefaceLoader
import ee.schimke.livery.protocol.AssetKind
import ee.schimke.livery.protocol.TemplateEntry
import ee.schimke.livery.publisher.CatalogBuilder

/**
 * A template's font families from the built catalog itself, as the app's
 * `LiveryTypefaceLoader.forTemplate` builds them from a downloaded one: the font assets the template
 * names, by family, weight and style. Families it doesn't ship fall back to the system font.
 */
internal object CatalogFonts {
  fun forTemplate(catalog: CatalogBuilder.Output, template: TemplateEntry): RcTypefaceLoader {
    val fonts = catalog.manifest.assets.filter { it.sha256 in template.assets && it.kind == AssetKind.Font && it.font != null }
    if (fonts.isEmpty()) return RcTypefaceLoader.Default
    val families = fonts.groupBy { it.font!!.family }.mapValues { (_, faces) ->
      FontFamily(faces.mapNotNull { a ->
        val bytes = asset(catalog, a.sha256) ?: return@mapNotNull null
        Font(a.sha256, bytes, FontWeight(a.font!!.weight), if (a.font!!.italic) FontStyle.Italic else FontStyle.Normal)
      })
    }
    return LiveryTypefaceLoader(families)
  }
}
