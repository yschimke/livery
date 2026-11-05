package ee.schimke.livery.client

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Marks work that must not wait on the network: a widget render, which a host gives a deadline.
 * Inside `withContext(CacheOnly())`, [TemplateRepository.ensure] (and so `SlotPlanner.forDecision`
 * and `forFeedItem`) plays only templates already on the device; a template or asset it would have
 * downloaded is recorded instead, and the call answers as if it were unavailable. The host draws
 * its fallback, then calls [fetchMissed] off the render path and renders again when it returns
 * true.
 *
 * The app's own planning code needs no change: the marker travels in the coroutine context.
 */
class CacheOnly : AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<CacheOnly>

  private val missed = ArrayList<suspend () -> Boolean>()

  /** How many downloads the work inside skipped. */
  val misses: Int get() = missed.size

  internal fun miss(fetch: suspend () -> Boolean) { missed += fetch }

  /** Performs the skipped downloads; true when there were some and all succeeded. */
  suspend fun fetchMissed(): Boolean = missed.isNotEmpty() && missed.toList().all { it() }
}
