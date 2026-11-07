package ee.schimke.livery.compose.material3

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import ee.schimke.livery.client.Bindings
import ee.schimke.livery.protocol.NamedValue
import ee.schimke.livery.protocol.ThemeRoles

/**
 * This Material 3 scheme as Livery's theme roles ([ThemeRoles.MATERIAL3]): one colour value per
 * role, `theme.primary` from [ColorScheme.primary] and so on, to pass beside a slot's data (its
 * `extra` bindings) so a template drawn with `theme` `host` takes the app's colours, dynamic
 * colour included. A template reads only the roles its contract declares; the rest are ignored.
 *
 * [surface] is what `theme.surface` gets, the colour a template draws its card on. It defaults to
 * [ColorScheme.surfaceContainerHigh], not [ColorScheme.surface], so a card stands off the screen
 * behind it in light and dark; pass [ColorScheme.surface] for a template that is the screen.
 * `theme.surfaceContainerHigh` and the other containers are always their own colours.
 */
fun ColorScheme.toLiveryTheme(surface: Color = surfaceContainerHigh): Bindings =
  Bindings(
    linkedMapOf(
        ThemeRoles.PRIMARY to primary,
        ThemeRoles.ON_PRIMARY to onPrimary,
        ThemeRoles.PRIMARY_CONTAINER to primaryContainer,
        ThemeRoles.ON_PRIMARY_CONTAINER to onPrimaryContainer,
        ThemeRoles.INVERSE_PRIMARY to inversePrimary,
        ThemeRoles.SECONDARY to secondary,
        ThemeRoles.ON_SECONDARY to onSecondary,
        ThemeRoles.SECONDARY_CONTAINER to secondaryContainer,
        ThemeRoles.ON_SECONDARY_CONTAINER to onSecondaryContainer,
        ThemeRoles.TERTIARY to tertiary,
        ThemeRoles.ON_TERTIARY to onTertiary,
        ThemeRoles.TERTIARY_CONTAINER to tertiaryContainer,
        ThemeRoles.ON_TERTIARY_CONTAINER to onTertiaryContainer,
        ThemeRoles.BACKGROUND to background,
        ThemeRoles.ON_BACKGROUND to onBackground,
        ThemeRoles.SURFACE to surface,
        ThemeRoles.ON_SURFACE to onSurface,
        ThemeRoles.SURFACE_VARIANT to surfaceVariant,
        ThemeRoles.ON_SURFACE_VARIANT to onSurfaceVariant,
        ThemeRoles.SURFACE_TINT to surfaceTint,
        ThemeRoles.INVERSE_SURFACE to inverseSurface,
        ThemeRoles.INVERSE_ON_SURFACE to inverseOnSurface,
        ThemeRoles.ERROR to error,
        ThemeRoles.ON_ERROR to onError,
        ThemeRoles.ERROR_CONTAINER to errorContainer,
        ThemeRoles.ON_ERROR_CONTAINER to onErrorContainer,
        ThemeRoles.OUTLINE to outline,
        ThemeRoles.OUTLINE_VARIANT to outlineVariant,
        ThemeRoles.SCRIM to scrim,
        ThemeRoles.SURFACE_BRIGHT to surfaceBright,
        ThemeRoles.SURFACE_DIM to surfaceDim,
        ThemeRoles.SURFACE_CONTAINER to surfaceContainer,
        ThemeRoles.SURFACE_CONTAINER_HIGH to surfaceContainerHigh,
        ThemeRoles.SURFACE_CONTAINER_HIGHEST to surfaceContainerHighest,
        ThemeRoles.SURFACE_CONTAINER_LOW to surfaceContainerLow,
        ThemeRoles.SURFACE_CONTAINER_LOWEST to surfaceContainerLowest,
      )
      .mapValues { (_, color) -> NamedValue.Color(color.toArgb().toLong() and 0xFFFFFFFFL) }
  )
