package ee.schimke.livery.capture.cmp

import ee.schimke.composeai.rccreation.common.Profile
import ee.schimke.composeai.rccreation.common.RcPlatformProfiles
import ee.schimke.composeai.rcplayer.edit.RcProfileViolation
import ee.schimke.composeai.rcplayer.edit.RcTargetProfile
import ee.schimke.composeai.rcplayer.edit.RcTargetProfiles
import ee.schimke.composeai.rcplayer.edit.validateFor
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.composeai.rcwidget.creation.RcCaptureProfiles
import ee.schimke.livery.protocol.Target

/**
 * The capture [Profile] a [Target]'s `profiles` name: what the writer holds a capture to (it refuses
 * an operation outside it and writes the fallback where it has one), and the header it declares.
 * The same choice `livery-capture` makes with AndroidX's profiles, on rc-players' equivalents
 * (`RcCaptureProfiles` is tested against `widgetsProfile` and `systemThemedWidgetsProfile`):
 *
 * - `widgets` (a launcher widget, a notification): [RcCaptureProfiles.launcherWidgets], which is
 *   `rc-widget-launcher`'s `widgetsProfile`: what Android 16's level 6 player and API 37's both
 *   decode, text as `TextLayout`, the widgets profile in the header, no measuring while painting.
 * - `glance-wear`: [RcCaptureProfiles.wearWidgets], Glance Wear's allow-list.
 * - `experimental`: [RcCaptureProfiles.androidXExperimental], AndroidX's experimental operations
 *   (a flow row, a pattern) for an app's own player.
 * - anything else (`androidx`): [RcPlatformProfiles.ANDROIDX], the creation default.
 */
internal fun Target.captureProfile(): Profile = when {
  "widgets" in profiles -> RcCaptureProfiles.launcherWidgets
  "glance-wear" in profiles -> RcCaptureProfiles.wearWidgets
  "experimental" in profiles -> RcCaptureProfiles.androidXExperimental
  else -> RcPlatformProfiles.ANDROIDX
}

/**
 * What in [bytes], a capture written under this profile, its player would still refuse; empty when
 * it holds none. The writer already refuses an operation outside the profile, so this is a check
 * of the result and not a second gate: it also covers what the writer has no say in.
 *
 * Deliberately not `rc-player-edit`'s whole `validateFor`. That reports a padding, a spacing or a
 * background colour that reads a variable as unreadable on Android 16, which is true of a document
 * as it stands but not of a template: a template reads named values and the display's density by
 * design, and the host makes them literal when it instantiates (`livery-instantiate`'s bake, then
 * `rc-widget-launcher`'s `hardcodeLayoutSpacing`). Rejecting those would reject every template
 * with a colour input. What is checked is what instantiation cannot change:
 * - an operation outside the profile (also refused by the writer);
 * - for a profile Android 16 reads (inside its level 6 table), a `DrawContent` inside a component's
 *   canvas operations (`drawWithContent`, a brush or painter background), which that player paints
 *   recursively until the launcher's widget host dies (`RcProfileViolation.Kind.RECURSIVE_DRAW_CONTENT`).
 */
internal fun Profile.problems(bytes: ByteArray): List<String> {
  val reads = RcTargetProfile.of(apiLevel, operationsProfiles)
  val document = reads.decode(bytes, RcWireLimits())
  val supported = supportedOperations
  val outside = document.operations.map { it.opcode }.filter { it !in supported }.distinct()
    .map { "operation $it is outside the capture profile" }
  val android16 = RcTargetProfiles.WIDGETS_V6
  val recursive = if (supported.all { it in android16.supportedOpcodes }) {
    document.validateFor(android16).filter { it.kind == RcProfileViolation.Kind.RECURSIVE_DRAW_CONTENT }.map { it.message }
  } else {
    emptyList()
  }
  return outside + recursive
}
