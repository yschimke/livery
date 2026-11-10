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
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.visibility
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.RemoteInt
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
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
 * The sample catalog's `poll-card` in-app template, against `social/poll@1`: up to four options,
 * each a row that votes when tapped, with a bar for its share of the votes. Everything that changes
 * as votes come in (shares, results, the mark on the chosen option, the summary) is a named value,
 * so new results are pushed into the document already on screen.
 *
 * Captured into the built sample catalog by `./gradlew :livery-capture:sampleCatalogs`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi")
class SamplePollTest {
  private val catalog = File("../samples/catalog")
  private val contract = LiveryCapture.contract(catalog, "social/poll@1")
  private val target = Target(Surfaces.IN_APP, 7, listOf("androidx"), size = SizeRange(minWidthDp = 280, maxWidthDp = 600))

  private suspend fun pollCard() = LiveryCapture.capture(RuntimeEnvironment.getApplication(), target, 360, 280, source = mapOf("design" to "poll-card")) {
    val v = LiveryValues(contract)
    val onSurface = v.color("theme.onSurface", Color(0xFF1D1B20))
    val primary = v.color("theme.primary", Color(0xFF6750A4))
    val count = v.int("options.count")
    RemoteColumn(
      RemoteModifier.fillMaxWidth().clip(RemoteRoundedCornerShape(16.rdp)).background(v.color("theme.surface", Color(0xFFF7F2FA))).padding(16.rdp),
      verticalArrangement = RemoteArrangement.spacedBy(10.rdp),
    ) {
      RemoteText(v.string("question"), color = onSurface, fontSize = 18.rsp, fontWeight = FontWeight.Bold)
      for (i in 0 until contract.lists.getValue("options").max) {
        // A row per option the data has; the rest are gone (Remote Compose's GONE is 0, VISIBLE 1).
        RemoteColumn(
          RemoteModifier.fillMaxWidth().visibility(count.isGreaterThan(RemoteInt(i)).toRemoteInt())
            .clip(RemoteRoundedCornerShape(12.rdp)).clickable(v.action("vote", mapOf("option" to "$i"))).padding(vertical = 4.rdp),
          verticalArrangement = RemoteArrangement.spacedBy(4.rdp),
        ) {
          RemoteRow(RemoteModifier.fillMaxWidth(), horizontalArrangement = RemoteArrangement.SpaceBetween, verticalAlignment = RemoteAlignment.CenterVertically) {
            RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically, horizontalArrangement = RemoteArrangement.spacedBy(6.rdp)) {
              RemoteText(v.string("options.$i.label"), color = onSurface, fontSize = 15.rsp)
              RemoteText(v.string("options.$i.mark"), color = primary, fontSize = 15.rsp, fontWeight = FontWeight.Bold)
            }
            RemoteText(v.string("options.$i.result"), color = onSurface, fontSize = 14.rsp)
          }
          // The track, and the share of it this option has.
          RemoteBox(RemoteModifier.fillMaxWidth().height(8.rdp).clip(RemoteRoundedCornerShape(4.rdp)).background(Color(0x1F79747E).rc)) {
            RemoteBox(RemoteModifier.fillMaxWidth(v.float("options.$i.share")).height(8.rdp).clip(RemoteRoundedCornerShape(4.rdp)).background(primary))
          }
        }
      }
      RemoteText(v.string("summary"), color = onSurface, fontSize = 13.rsp)
    }
  }

  @Test fun capturesPollCard() = runBlocking {
    val templates = mapOf("in-app" to pollCard())
    assertEquals(listOf("vote"), templates.getValue("in-app").emits)
    SampleCatalogs.write("catalog", "poll-card", templates)
  }
}
