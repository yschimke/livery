package ee.schimke.livery.compose

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

internal actual fun <T> awaitBlocking(deferred: Deferred<T>, millis: Long): T? =
  if (millis <= 0) null else runBlocking { withTimeoutOrNull(millis) { deferred.await() } }
