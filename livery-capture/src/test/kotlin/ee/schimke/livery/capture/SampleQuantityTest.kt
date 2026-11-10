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
import androidx.compose.remote.creation.compose.modifier.size
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
 * The two-way sample catalog's `quantity-stepper` in-app template, against `shop/quantity@1`: a two-way
 * control. Its `−` and `+` send `setQuantity` with the quantity the document computes when tapped
 * (`quantity - 1`, `quantity + 1`); the host writes it back, and the new value comes back in as the
 * `quantity` input. The document keeps no state of its own, so it always shows the host's value,
 * and a step past the contract's 0…9 is refused by the client, not by the design.
 *
 * Captured into the built sample catalog by `./gradlew :livery-capture:sampleCatalogs`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi")
class SampleQuantityTest {
  private val catalog = File("../samples/two-way")
  private val contract = LiveryCapture.contract(catalog, "shop/quantity@1")
  private val target = Target(Surfaces.IN_APP, 7, listOf("androidx"), size = SizeRange(minWidthDp = 240, maxWidthDp = 600))

  private suspend fun stepper() = LiveryCapture.capture(RuntimeEnvironment.getApplication(), target, 360, 80, source = mapOf("design" to "quantity-stepper")) {
    val v = LiveryValues(contract)
    val onSurface = v.color("theme.onSurface", Color(0xFF1D1B20))
    val primary = v.color("theme.primary", Color(0xFF6750A4))
    val quantity = v.int("quantity")
    RemoteRow(
      RemoteModifier.fillMaxWidth().clip(RemoteRoundedCornerShape(16.rdp)).background(v.color("theme.surface", Color(0xFFF7F2FA))).padding(16.rdp),
      horizontalArrangement = RemoteArrangement.SpaceBetween,
      verticalAlignment = RemoteAlignment.CenterVertically,
    ) {
      RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(2.rdp)) {
        RemoteText(v.string("name"), color = onSurface, fontSize = 16.rsp, fontWeight = FontWeight.Medium)
        RemoteText(v.string("price"), color = onSurface, fontSize = 13.rsp)
      }
      RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically, horizontalArrangement = RemoteArrangement.spacedBy(12.rdp)) {
        RemoteBox(RemoteModifier.size(36.rdp).clip(RemoteRoundedCornerShape(18.rdp)).background(primary).clickable(v.set("setQuantity", quantity - 1)), contentAlignment = RemoteAlignment.Center) {
          RemoteText("−".rs, color = Color.White.rc, fontSize = 20.rsp, fontWeight = FontWeight.Bold)
        }
        RemoteText(quantity.toRemoteString(), color = onSurface, fontSize = 18.rsp, fontWeight = FontWeight.Bold)
        RemoteBox(RemoteModifier.size(36.rdp).clip(RemoteRoundedCornerShape(18.rdp)).background(primary).clickable(v.set("setQuantity", quantity + 1)), contentAlignment = RemoteAlignment.Center) {
          RemoteText("+".rs, color = Color.White.rc, fontSize = 20.rsp, fontWeight = FontWeight.Bold)
        }
      }
    }
  }

  @Test fun capturesQuantityStepper() = runBlocking {
    val templates = mapOf("in-app" to stepper())
    assertEquals(listOf("setQuantity"), templates.getValue("in-app").emits)
    SampleCatalogs.write("two-way", "quantity-stepper", templates)
  }

  @Test fun setChecksTheActionAndTheInputsType() = runBlocking {
    val v = LiveryValues(contract)
    assertEquals(
      "shop/quantity@1 action 'setQuantity' sets quantity, a int, not string",
      kotlin.test.assertFailsWith<IllegalArgumentException> { v.set("setQuantity", "3".rs) }.message,
    )
    Unit
  }
}
