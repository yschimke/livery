package ee.schimke.livery.capture.samples

import androidx.compose.ui.text.font.FontWeight

// What the pinned AndroidX alpha spells differently from rc-players' creation API (which follows
// androidx-main), for the generated SampleDesigns.kt: livery-capture-cmp's tests have their own
// copy of this file.

/** AndroidX's alpha takes Compose's `FontWeight` here; androidx-main a `RemoteFontWeight`. */
internal val Bold = FontWeight.Bold
internal val Medium = FontWeight.Medium
