package ee.schimke.livery.capture.cmp

import okio.ByteString.Companion.toByteString
import androidx.compose.runtime.Composable
import ee.schimke.composeai.rccreation.common.Profile
import ee.schimke.composeai.rccreation.compose.capture.RemoteCreationDisplayInfo
import ee.schimke.composeai.rccreation.compose.capture.RemoteDensity
import ee.schimke.composeai.rccreation.compose.capture.captureCommonRemoteDocument
import ee.schimke.livery.capture.CapturedTemplate
import ee.schimke.livery.protocol.Target

/**
 * Captures a `@RemoteComposable` written against rc-players' Compose Multiplatform creation API
 * (`ee.schimke.composeai.rccreation`) for a [Target]: no `Context`, no Robolectric, so from a plain
 * JVM test, a build tool, a server or a browser. The sidecar's reads and emits come from the
 * captured document itself, so they can't drift from what the template does.
 *
 * Content composed with AndroidX's own `remote-creation-compose` (and the component libraries built
 * on it) is captured by `livery-capture`'s `ee.schimke.livery.capture.LiveryCapture` instead.
 */
object LiveryCapture {
  /**
   * Captures [content] at [widthDp] × [heightDp] (a size inside [target]'s range) for [target].
   * [source] goes into the sidecar (`tool` is `capture` unless it names another).
   *
   * The capture is written for [profile], and the writer holds it to that: an operation outside the
   * profile fails the capture with the writer's own message, text is a `TextLayout` where the
   * profile has no `CoreText`, and the header is the profile's. Nothing is rewritten afterwards,
   * as in `livery-capture`. The default follows [target]'s `profiles`, as `livery-capture` does:
   * - `widgets` (a launcher widget or a notification): `RcCaptureProfiles.launcherWidgets`, what
   *   Android 16's level 6 player and API 37's both decode (`rc-widget-launcher`'s `widgetsProfile`).
   *   Pass `RcCaptureProfiles.systemThemedLauncherWidgets` for API 37 launchers only, or
   *   `RcTargetProfile.of(7, …).toCaptureProfile(…)` for a launcher whose table differs.
   * - `glance-wear`: `RcCaptureProfiles.wearWidgets`, Glance Wear's allow-list.
   * - `experimental`: `RcCaptureProfiles.androidXExperimental`.
   * - otherwise `RcPlatformProfiles.ANDROIDX`.
   *
   * What a template holds that a player can't read once its values are filled in (a padding or a
   * background colour that reads a named value on Android 16) is not checked here: the capture is
   * a template, and the host's instantiation (`livery-instantiate`, `rc-widget-launcher`) makes
   * those literal. `livery-verify` plays an instantiated capture on Android 16's own player.
   */
  suspend fun capture(
    target: Target,
    widthDp: Int,
    heightDp: Int,
    source: Map<String, String> = emptyMap(),
    profile: Profile = target.captureProfile(),
    content: @Composable () -> Unit,
  ): CapturedTemplate {
    val bytes = captureCommonRemoteDocument(
      creationDisplayInfo = RemoteCreationDisplayInfo(widthDp, heightDp, 160),
      remoteDensity = RemoteDensity.Host,
      profile = profile,
      content = content,
    )
    profile.problems(bytes).takeIf { it.isNotEmpty() }?.let { problems ->
      throw IllegalStateException("the capture wouldn't play as ${target.profiles.joinToString("+")} on ${target.surface}: ${problems.joinToString("; ")}")
    }
    return CapturedTemplate.of(bytes.toByteString(), target, source)
  }
}
