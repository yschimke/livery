package ee.schimke.livery.protocol

import kotlinx.serialization.Serializable

/**
 * What a document was built for. A client describes what it can play as [PlayerCapabilities] and
 * picks the entry whose target fits ([fits]).
 *
 * @property surface where it is drawn: [Surfaces]. Open-ended: an app may add its own.
 * @property rcApiLevel Remote Compose document API level the document needs.
 * @property profiles operation profiles the document stays inside (`androidx`, `widgets`,
 *   `glance-wear`, `experimental`, …). A player must support every one listed.
 * @property size the sizes the document lays out for. One document carries its own size tiers.
 * @property theme `host` (colours pushed as `theme.*` / ColorTheme) or `baked:<light|dark>`.
 * @property container a fixed host container (`wear:small`, `wear:large`), or null.
 */
@Serializable
data class Target(
  val surface: String,
  val rcApiLevel: Int,
  val profiles: List<String> = emptyList(),
  val size: SizeRange = SizeRange(),
  val theme: String = THEME_HOST,
  val container: String? = null,
) {
  companion object {
    const val THEME_HOST: String = "host"
    fun baked(dark: Boolean): String = if (dark) "baked:dark" else "baked:light"
  }
}

object Surfaces {
  const val IN_APP: String = "in-app"
  const val LAUNCHER_WIDGET: String = "launcher-widget"
  const val WEAR_WIDGET: String = "wear-widget"
  /** A notification's expanded body, played by SystemUI (Android 16+). */
  const val NOTIFICATION: String = "notification"
  const val WEAR_APP: String = "wear-app"
  const val WEB: String = "web"
  const val DESKTOP: String = "desktop"
  const val TV: String = "tv"
  /** Rendered server-side to bytes or an image, e.g. an email snapshot. */
  const val SNAPSHOT: String = "snapshot"
}

/** Inclusive dp ranges; null is unbounded. A wrap-width document leaves the heights null. */
@Serializable
data class SizeRange(
  val minWidthDp: Int? = null,
  val maxWidthDp: Int? = null,
  val minHeightDp: Int? = null,
  val maxHeightDp: Int? = null,
) {
  fun covers(widthDp: Int?, heightDp: Int?): Boolean =
    (widthDp == null || ((minWidthDp ?: Int.MIN_VALUE) <= widthDp && widthDp <= (maxWidthDp ?: Int.MAX_VALUE))) &&
      (heightDp == null || ((minHeightDp ?: Int.MIN_VALUE) <= heightDp && heightDp <= (maxHeightDp ?: Int.MAX_VALUE)))
}

/** What one player on one device can draw. */
@Serializable
data class PlayerCapabilities(
  val surface: String,
  val maxRcApiLevel: Int,
  val profiles: Set<String>,
  /** Whether this host can push named values after the first frame. Widgets can't. */
  val livePush: Boolean = true,
  val themes: Set<String> = setOf(Target.THEME_HOST),
  val container: String? = null,
  /** The largest document this host accepts (a launcher widget's budget is small). */
  val maxBytes: Long = Long.MAX_VALUE,
)

/** Surface, level, profiles, container and size; theme is matched by the selector. */
fun Target.fits(caps: PlayerCapabilities, widthDp: Int? = null, heightDp: Int? = null): Boolean =
  surface == caps.surface &&
    rcApiLevel <= caps.maxRcApiLevel &&
    caps.profiles.containsAll(profiles) &&
    container == caps.container &&
    size.covers(widthDp, heightDp)

/**
 * A host-themed document fits any host that pushes theme roles; a baked one only the theme asked
 * for (or, with none asked, any baked theme the host accepts).
 */
fun Target.themeFits(caps: PlayerCapabilities, wanted: String?): Boolean =
  if (theme == Target.THEME_HOST) Target.THEME_HOST in caps.themes
  else if (wanted != null) theme == wanted
  else theme in caps.themes
