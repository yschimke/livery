package ee.schimke.livery.twins

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The sample catalog's Compose-authored designs (livery-capture-cmp's samples/SampleDesigns.kt),
// written again directly in Compose UI: the same layout, sizes, colours and text, node for node, so
// a feed of either draws the same cards. The host's benchmark flings both; livery-compose's
// TwinRenderParityTest holds them to the same pixels as the templates.

/** One `promo/banner@1` card's data. */
class BannerData(val headline: String, val body: String, val cta: String)

/** One `social/poll@1` option. */
class PollOption(val label: String, val share: Float, val result: String, val mark: String = "")

/** One `social/poll@1` card's data. */
class PollData(val question: String, val summary: String, val options: List<PollOption>)

/** `banner-classic` (SampleDesigns.kt `BannerClassic`), with the template's default colours. */
@Composable
fun BannerTwin(data: BannerData, modifier: Modifier = Modifier) {
  Column(
    modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      BasicText(data.headline, style = TextStyle(OnSurface, 20.sp, FontWeight.Bold))
      BasicText("✕", Modifier.clickable {}.padding(4.dp), style = TextStyle(OnSurface, 18.sp))
    }
    BasicText(data.body, style = TextStyle(OnSurface, 14.sp))
    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Accent).clickable {}.padding(horizontal = 20.dp, vertical = 10.dp)) {
      BasicText(data.cta, style = TextStyle(Color.White, 14.sp, FontWeight.Medium))
    }
  }
}

/**
 * `poll-card` (SampleDesigns.kt `PollCard`). The template has a row for each of the contract's
 * [MAX_OPTIONS] options and hides the ones the data has none for; here those rows are not composed,
 * which is what Compose UI would write.
 */
@Composable
fun PollTwin(data: PollData, modifier: Modifier = Modifier) {
  Column(
    modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    BasicText(data.question, style = TextStyle(OnSurface, 18.sp, FontWeight.Bold))
    for (option in data.options.take(MAX_OPTIONS)) {
      Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {}.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText(option.label, style = TextStyle(OnSurface, 15.sp))
            BasicText(option.mark, style = TextStyle(Primary, 15.sp, FontWeight.Bold))
          }
          BasicText(option.result, style = TextStyle(OnSurface, 14.sp))
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Track)) {
          Box(Modifier.fillMaxWidth(option.share).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Primary))
        }
      }
    }
    BasicText(data.summary, style = TextStyle(OnSurface, 13.sp))
  }
}

const val MAX_OPTIONS = 4
private val Surface = Color(0xFFF7F2FA)
private val OnSurface = Color(0xFF1D1B20)
private val Primary = Color(0xFF6750A4)
private val Accent = Color(0xFF6750A4)
private val Track = Color(0x1F79747E)
