package ee.schimke.livery.compose

import kotlinx.coroutines.Deferred

/** The browser has one thread, and it cannot wait on itself: a first frame there never waits. */
internal actual fun <T> awaitBlocking(deferred: Deferred<T>, millis: Long): T? = null

@androidx.compose.runtime.Composable internal actual fun windowHasDrawn(): Boolean = false
