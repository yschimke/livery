package ee.schimke.livery.compose

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

internal actual fun <T> awaitBlocking(deferred: Deferred<T>, millis: Long): T? =
  if (millis <= 0) null else runBlocking { withTimeoutOrNull(millis) { deferred.await() } }

/** The window's last draw time, 0 until it has drawn a frame. */
@androidx.compose.runtime.Composable
internal actual fun windowHasDrawn(): Boolean =
  androidx.compose.ui.platform.LocalView.current.drawingTime != 0L
