@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.livery.capture

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import ee.schimke.livery.protocol.SizeRange
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.Target
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The sample catalog's `banner-classic` in-app template, authored here against `promo/banner@1`
 * and checked against `samples/catalog/designs/banner-classic/`. After changing the composable,
 * regenerate with `./gradlew :livery-capture:testDebugUnitTest -Plivery.capture.write=true`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi")
class SampleBannerTest {
  private val catalog = File("../samples/catalog")
  private val contract = LiveryCapture.contract(catalog, "promo/banner@1")
  private val target = Target(Surfaces.IN_APP, 7, listOf("androidx"), size = SizeRange(minWidthDp = 280, maxWidthDp = 840))

  private suspend fun classic() = LiveryCapture.capture(RuntimeEnvironment.getApplication(), target, 360, 160, source = mapOf("design" to "banner-classic")) {
    val v = LiveryValues(contract)
    RemoteColumn(
      RemoteModifier.fillMaxWidth().clip(RemoteRoundedCornerShape(16.rdp)).background(v.color("theme.surface", Color(0xFFF7F2FA))).padding(16.rdp),
      verticalArrangement = RemoteArrangement.spacedBy(8.rdp),
    ) {
      RemoteRow(RemoteModifier.fillMaxWidth(), horizontalArrangement = RemoteArrangement.SpaceBetween, verticalAlignment = RemoteAlignment.CenterVertically) {
        RemoteText(v.string("headline"), color = v.color("theme.onSurface", Color(0xFF1D1B20)), fontSize = 20.rsp, fontWeight = FontWeight.Bold)
        RemoteText("✕".rs, modifier = RemoteModifier.clickable(v.action("dismiss")).padding(4.rdp), color = v.color("theme.onSurface", Color(0xFF1D1B20)), fontSize = 18.rsp)
      }
      RemoteText(v.string("body"), color = v.color("theme.onSurface", Color(0xFF1D1B20)), fontSize = 14.rsp)
      RemoteBox(
        RemoteModifier.clip(RemoteRoundedCornerShape(20.rdp)).background(v.color("accent")).clickable(v.action("cta", mapOf("route" to "/premium"))).padding(horizontal = 20.rdp, vertical = 10.rdp),
      ) {
        RemoteText(v.string("cta"), color = Color.White.rc, fontSize = 14.rsp, fontWeight = FontWeight.Medium)
      }
    }
  }

  @Test fun bannerClassicIsCurrent() = runBlocking {
    val templates = mapOf("in-app" to classic())
    assertEquals(listOf("cta", "dismiss"), templates.getValue("in-app").emits)
    val folder = DesignFolder(File(catalog, "designs/banner-classic"))
    if (System.getProperty("livery.capture.write") == "true") folder.write(templates)
    assertEquals(emptyList(), folder.check(templates), "regenerate with -Plivery.capture.write=true")
  }
}
