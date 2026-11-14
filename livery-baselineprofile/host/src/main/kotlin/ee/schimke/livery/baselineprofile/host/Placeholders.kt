package ee.schimke.livery.baselineprofile.host

import android.os.Trace
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ee.schimke.livery.compose.LiveryPlaceholder

/**
 * The feeds' placeholder, for a card that is not ready when it scrolls in. Each one composed leaves a
 * `host:placeholder` slice in the trace, which the benchmarks count: how many cards reached the screen
 * unprepared, which is what preparing ahead is for.
 */
@Composable
internal fun FeedPlaceholder(@Suppress("UNUSED_PARAMETER") placeholder: LiveryPlaceholder) {
  remember {
    Trace.beginSection(PLACEHOLDER_SECTION)
    Trace.endSection()
  }
  Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(96.dp).background(Color(0x11000000)))
}

internal const val PLACEHOLDER_SECTION = "host:placeholder"
