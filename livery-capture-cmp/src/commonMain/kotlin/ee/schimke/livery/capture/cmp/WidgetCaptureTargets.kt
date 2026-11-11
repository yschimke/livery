package ee.schimke.livery.capture.cmp

import ee.schimke.composeai.rcplayer.edit.RcApproximation
import ee.schimke.composeai.rcplayer.edit.RcDisplay
import ee.schimke.composeai.rcplayer.edit.RcProfiles
import ee.schimke.composeai.rcplayer.edit.RcTargetProfile
import ee.schimke.composeai.rcplayer.edit.RcTargetProfiles
import ee.schimke.composeai.rcplayer.edit.adaptTo
import ee.schimke.composeai.rcplayer.edit.hardcodeLayoutSpacing
import ee.schimke.composeai.rcplayer.edit.validateFor
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcHeaderProperty
import ee.schimke.composeai.rcplayer.protocol.RcHeaderValue
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.composeai.rcwidget.RcWidgetTarget
import ee.schimke.composeai.rcwidget.RcWidgetTargets
import ee.schimke.livery.protocol.Target

/**
 * The player a [Target]'s profiles name, as rc-players describes it, and so what a capture for it
 * has to be.
 *
 * AndroidX's creation API takes a `Profile` object for this (`rc-widget-launcher`'s
 * `widgetsProfile`, Glance Wear's `wearWidgets`), which makes its writer take a fallback for each
 * operation the player lacks. rc-players' Compose Multiplatform capture has no `Profile`: its
 * `supportedOperations` only answers a component that asks (`currentProfileSupports…`), and its
 * writer writes `CoreText` whatever the reader is. So the same thing is done here with
 * rc-player-edit, from the same tables (`RcTargetProfiles`): a launcher capture is rewritten into
 * what the launcher decodes ([forPlayer]), and each capture is checked the way its host hands it to
 * the player ([problems]).
 */
internal class WidgetCaptureTargets private constructor(
  /** What the document may hold, or null to keep it as captured. */
  private val vocabulary: RcTargetProfile?,
  /** The players the document must play on, each after its host's adaptation. */
  private val players: List<RcWidgetTarget>,
  private val launcher: Boolean,
) {
  /** The operations a component is told the reader takes (`currentProfileSupports…`). */
  val operations: Set<Int> = (vocabulary?.supportedOpcodes ?: players.map { it.profile.supportedOpcodes }.reduce(Set<Int>::intersect))

  /**
   * [captured] as the document for this player. A launcher's is rewritten: `CoreText` becomes the
   * `TextLayout` Android 16 reads (what AndroidX's writer falls back to under `widgetsProfile`),
   * and the header says what that profile's says: the widgets profile, and no measuring while
   * painting, which a launcher's resized view depends on. Nothing display-specific is folded in:
   * the template stays one document for every device, as an AndroidX capture is.
   */
  fun forPlayer(captured: ByteArray): ByteArray {
    val vocabulary = vocabulary ?: return captured
    val adapted = vocabulary.decode(captured, RcWireLimits()).adaptTo(vocabulary, setOf(RcApproximation.TEXT))
    check(adapted.isPlayable) { "the capture holds what ${vocabulary.name} can't read: ${adapted.violations.joinToString("; ") { it.message }}" }
    return RcDocumentCodec.encode(adapted.document.withWidgetHeader())
  }

  /** Why a player would refuse [bytes], after its host's own adaptation; empty when each plays it. */
  fun problems(bytes: ByteArray): List<String> = players.flatMap { player ->
    val profile = player.profile
    val decoded = profile.decode(bytes, RcWireLimits())
    DISPLAYS.flatMap { display ->
      val violations = if (launcher) {
        // rc-widget-launcher's RcLauncherWidgets.forDisplay: the player lays a layout whose spacing
        // is a variable out as nothing, so each is made a literal; what it still can't read is then
        // adapted for the display.
        val spaced = decoded.hardcodeLayoutSpacing(display.density, display.fontScale)
        spaced.validateFor(profile).ifEmpty { return@flatMap emptyList() }
        spaced.adaptTo(profile, player.approximations, display).violations
      } else {
        // rc-widget-core's RcWearTemplates.instantiate, which livery-wear-widgets publishes through.
        decoded.adaptTo(profile, player.approximations, display).violations
      }
      violations.map { "${profile.name}: ${it.message}" }
    }.distinct()
  }

  private fun RcDocument.withWidgetHeader(): RcDocument {
    val kept = header.properties.filterNot { it.key == DOC_PROFILES || it.key == FEATURE_PAINT_MEASURE }
    val widget = listOf(
      RcHeaderProperty(DOC_PROFILES, RcHeaderValue.IntValue(RcProfiles.WIDGETS or RcProfiles.EXPERIMENTAL)),
      RcHeaderProperty(FEATURE_PAINT_MEASURE, RcHeaderValue.IntValue(0)),
    )
    return copy(header = header.copy(properties = kept + widget))
  }

  companion object {
    /** A launcher at 1x and at a phone's density: the host's adaptation folds the density in. */
    private val DISPLAYS = listOf(RcDisplay(1f), RcDisplay.DEFAULT)

    // AndroidX `Header.DOC_PROFILES` and `Header.FEATURE_PAINT_MEASURE`.
    private const val DOC_PROFILES = 14
    private const val FEATURE_PAINT_MEASURE = 15

    /**
     * AndroidX admits these to its experimental level 7 widget set, but Android 16's platform
     * decoder has none of them. The same three `rc-widget-launcher`'s `widgetsProfile` leaves out.
     */
    private val NOT_ON_A_LAUNCHER = setOf(RcOpcodes.CORE_TEXT, RcOpcodes.TEXT_STYLE, RcOpcodes.LAYOUT_FLOW)

    /**
     * What a launcher capture may hold: the level 7 widget operations Android 16's level 6 player
     * decodes too, as `widgetsProfile` (so no `ColorTheme`, which only an API 37 launcher resolves).
     */
    private val LAUNCHER: RcTargetProfile = RcTargetProfiles.WIDGETS_V7.let { v7 ->
      val absent = v7.supportedOpcodes - (RcTargetProfiles.WIDGETS_V6.supportedOpcodes - NOT_ON_A_LAUNCHER)
      v7.without(*absent.toIntArray(), name = "a launcher widget (what Android 16 and API level 7 launchers both read)")
    }

    /** What [target]'s profiles hold a capture to, or null for a player with no such limit (an app's own). */
    fun of(target: Target): WidgetCaptureTargets? = when {
      "widgets" in target.profiles ->
        WidgetCaptureTargets(LAUNCHER, listOf(RcWidgetTargets.LauncherAndroid16, RcWidgetTargets.Launcher), launcher = true)
      "glance-wear" in target.profiles -> WidgetCaptureTargets(null, listOf(RcWidgetTargets.WearWidget), launcher = false)
      else -> null
    }
  }
}
