package ee.schimke.livery.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Deferred

/**
 * Waits up to [millis] on the calling thread for [deferred], for its value or null at the limit;
 * null at once where a thread cannot wait (the browser).
 */
internal expect fun <T> awaitBlocking(deferred: Deferred<T>, millis: Long): T?

/** Marks [resources]' first frame drawn, after it is: from then on nothing waits for one. */
@Composable
internal fun MarkFirstFrameDrawn(resources: LiveryResources) {
  if (resources.firstFrameDrawn) return
  LaunchedEffect(resources) {
    withFrameNanos {}
    resources.firstFrameDrawn = true
  }
}
