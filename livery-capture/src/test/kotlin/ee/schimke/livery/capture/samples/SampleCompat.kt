package ee.schimke.livery.capture.samples

import androidx.compose.remote.creation.compose.text.RemoteFontWeight

// What the pinned AndroidX alpha spells differently from rc-players' creation API (which follows
// androidx-main), for the generated SampleDesigns.kt: livery-capture-cmp's tests have their own
// copy of this file.

/** A `RemoteFontWeight` in both since AndroidX alpha24; kept as the seam for the next difference. */
internal val Bold = RemoteFontWeight.Bold
internal val Medium = RemoteFontWeight.Medium
