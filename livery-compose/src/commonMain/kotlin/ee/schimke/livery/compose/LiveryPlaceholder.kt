package ee.schimke.livery.compose

import androidx.compose.runtime.staticCompositionLocalOf
import ee.schimke.livery.protocol.Contract

/**
 * What a card that cannot draw yet stands in for, handed to [LiveryHost.placeholder]: a template's
 * first card that nobody prepared, or a slot still planning, once the screen's first frame has
 * drawn (before it, [LiveryHost.firstFrameBudgetMillis] waits for the card instead).
 *
 * A template's placeholder is measured at the height the template last drew at for its width, when
 * it has drawn before, so the list does not move when the card arrives. Without one, or in a slot
 * still planning, the placeholder takes its own height (a skeleton's), or none.
 */
data class LiveryPlaceholder(
  /** The slot it is in ([LiverySlot]'s `slotId`), when the slot has one. */
  val slotId: String? = null,
  /** The contract the card will show, once a slot has planned it. */
  val contract: Contract? = null,
  /** The design the card will draw, once a slot has planned it. */
  val designId: String? = null,
)

/** The [LiverySlot] a template draws in, for its placeholder. */
internal val LocalLiverySlotId = staticCompositionLocalOf<String?> { null }

/**
 * Told by a template that finds, as it composes, that the player cannot play it: the [LiverySlot]
 * it draws in, so the slot draws the app's fallback rather than nothing. A host that prepares in
 * composition learns this only there.
 */
internal val LocalLiveryCannotPlay = staticCompositionLocalOf<((String) -> Unit)?> { null }
