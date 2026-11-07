package ee.schimke.livery.compose.material3

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import ee.schimke.livery.protocol.NamedValue
import ee.schimke.livery.protocol.ThemeRoles
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveryMaterialThemeTest {
  @Test fun everyMaterial3RoleInOrder() {
    val theme = lightColorScheme().toLiveryTheme()
    assertEquals(ThemeRoles.MATERIAL3, theme.values.keys.toList())
  }

  @Test fun rolesAreTheSchemesColoursAsArgb() {
    val scheme = lightColorScheme(primary = Color(0xFF6750A4), onSurfaceVariant = Color(0xFF49454F), surfaceContainerHigh = Color(0xFFECE6F0))
    val theme = scheme.toLiveryTheme().values
    assertEquals(NamedValue.Color(0xFF6750A4), theme[ThemeRoles.PRIMARY])
    assertEquals(NamedValue.Color(0xFF49454F), theme[ThemeRoles.ON_SURFACE_VARIANT])
    assertEquals(NamedValue.Color(0xFFECE6F0), theme[ThemeRoles.SURFACE_CONTAINER_HIGH])
  }

  @Test fun surfaceIsTheCardSurface() {
    val scheme = darkColorScheme(surface = Color(0xFF141218), surfaceContainerHigh = Color(0xFF2B2930))
    assertEquals(NamedValue.Color(0xFF2B2930), scheme.toLiveryTheme().values[ThemeRoles.SURFACE])
    assertEquals(NamedValue.Color(0xFF141218), scheme.toLiveryTheme(surface = scheme.surface).values[ThemeRoles.SURFACE])
  }

  @Test fun translucentColoursKeepTheirAlpha() {
    val theme = lightColorScheme(scrim = Color(0x80000000)).toLiveryTheme().values
    assertEquals(NamedValue.Color(0x80000000), theme[ThemeRoles.SCRIM])
  }
}
