package ee.schimke.livery.compose

import kotlinx.coroutines.CompletableDeferred
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether an app's first screen can draw without a blank or fallback frame: its slots planned and
 * their documents, fonts and images in memory. Hand [isReady] to the platform's splash screen so it
 * stays up until then, and no longer:
 *
 * ```kotlin
 * // Android, androidx.core:core-splashscreen, in Activity.onCreate before setContent
 * val startup = host.resources.startup(lifecycleScope) {
 *   prepareSlot("home.top", data) { planner.forAppData(query, data) }
 * }
 * installSplashScreen().setKeepOnScreenCondition { !startup.isReady }
 * ```
 *
 * On other platforms, [await] it before showing the first screen (iOS's launch screen stays until
 * the first view controller appears; the web keeps its loading state).
 */
class LiveryStartup internal constructor() {
  private val done = CompletableDeferred<Unit>()

  /** True once the first screen is prepared, its preparation failed, or the time limit passed. */
  val isReady: Boolean get() = done.isCompleted

  /** Suspends until [isReady]. */
  suspend fun await() = done.await()

  internal fun finish() {
    done.complete(Unit)
  }
}

/**
 * Prepares an app's first screen in [scope] (usually `prepareSlot` for each of its slots) and
 * reports when it is ready, for a splash screen to wait on ([LiveryStartup]).
 *
 * [prepare] runs on [context], a worker by default, from the moment this is called. An activity's
 * `lifecycleScope` runs on the main thread, and there [prepare] would run its first steps (resolving
 * a template, reading the bundled catalog) inside `onCreate`, then wait for the main thread to come
 * free again after the activity is created, when the first frame is already due. On a worker it
 * overlaps the activity's creation instead, and is usually done before that frame.
 *
 * Ready is never later than [timeoutMillis]: [prepare] reads the disk, but an image it preloads may
 * come from the network, and a splash screen must not wait on that. Past the limit the screen
 * draws, slots without an answer show their fallback, and [prepare] carries on in the background,
 * so what it finishes still draws on its slot's first frame. A failure in [prepare] also ends the
 * wait; the slots then plan for themselves as they would have without a startup.
 */
fun LiveryResources.startup(
  scope: CoroutineScope,
  timeoutMillis: Long = DEFAULT_STARTUP_TIMEOUT_MILLIS,
  context: CoroutineContext = Dispatchers.Default,
  prepare: suspend LiveryResources.() -> Unit,
): LiveryStartup {
  val startup = LiveryStartup()
  // The splash screen this startup holds covers the first frame: nothing need wait on the main
  // thread for it too (LiveryHost.firstFrameBudgetMillis).
  firstFrameCovered = true
  val work = scope.launch(context) {
    try {
      // How long the first screen took to prepare, for a trace or a benchmark to set against the
      // first frame: `livery:startup`.
      ee.schimke.livery.client.LiveryTrace.async({ "livery:startup" }) { prepare() }
    } catch (e: Exception) {
      if (e is kotlinx.coroutines.CancellationException) throw e
    } finally {
      startup.finish()
    }
  }
  scope.launch {
    try {
      withTimeoutOrNull(timeoutMillis) { work.join() }
    } finally {
      startup.finish()
    }
  }
  return startup
}

/** Long enough to read a first screen from disk, short enough that nobody stares at a logo. */
const val DEFAULT_STARTUP_TIMEOUT_MILLIS: Long = 800
