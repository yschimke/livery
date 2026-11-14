@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.livery.capture

import okio.ByteString.Companion.toByteString
import android.content.Context
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.RemoteDensity
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.profile.Profile
import androidx.compose.runtime.Composable
import ee.schimke.composeai.rcwidget.launcher.widgetsProfile
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.Target
import java.io.File

/**
 * Captures a `@RemoteComposable` for a [Target] (AndroidX remote-creation-compose), from a unit test
 * (Robolectric) or a build tool. The sidecar's reads and emits come from the captured document
 * itself, so they can't drift from what the template does.
 *
 * Content written against rc-players' Compose Multiplatform creation API is captured with no
 * `Context` by `livery-capture-cmp`'s `ee.schimke.livery.capture.cmp.LiveryCapture` instead.
 * `LiveryValues` here is that module's, generated against AndroidX's types.
 */
object LiveryCapture {
  /** The newest minor of [ref] (`id@major`) in a catalog source tree's `contracts/` ([CatalogContracts]). */
  fun contract(catalog: File, ref: String): Contract = CatalogContracts.contract(catalog, ref)

  /**
   * Captures [content] at [widthDp] × [heightDp] (a size inside [target]'s range) for [target].
   * [profile] defaults to the widgets profile for a `widgets` target and the creation default
   * otherwise. [source] goes into the sidecar (`tool` is `capture` unless it names another).
   */
  suspend fun capture(
    context: Context,
    target: Target,
    widthDp: Int,
    heightDp: Int,
    source: Map<String, String> = emptyMap(),
    profile: Profile? = if ("widgets" in target.profiles) widgetsProfile else null,
    content: @Composable () -> Unit,
  ): CapturedTemplate {
    val display = RemoteCreationDisplayInfo(widthDp, heightDp, 160)
    val bytes = if (profile != null) {
      captureSingleRemoteDocument(context = context, creationDisplayInfo = display, remoteDensity = RemoteDensity.Host, profile = profile, content = content).bytes
    } else {
      captureSingleRemoteDocument(context = context, creationDisplayInfo = display, remoteDensity = RemoteDensity.Host, content = content).bytes
    }
    return CapturedTemplate.of(bytes.toByteString(), target, source)
  }
}
