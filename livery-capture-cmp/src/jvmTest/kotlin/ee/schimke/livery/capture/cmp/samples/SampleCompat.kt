package ee.schimke.livery.capture.cmp.samples

import ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight

// What rc-players' creation API (as androidx-main has it) and the pinned AndroidX alpha spell
// differently, for SampleDesigns.kt: livery-capture's tests have their own copy of this file.

/** AndroidX's alpha takes Compose's `FontWeight` here; androidx-main a `RemoteFontWeight`. */
internal val Bold = RemoteFontWeight.Bold
internal val Medium = RemoteFontWeight.Medium
