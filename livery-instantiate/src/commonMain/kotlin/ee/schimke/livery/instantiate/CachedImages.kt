package ee.schimke.livery.instantiate

import ee.schimke.livery.client.ImageResolver

/** An image a render asked for, at the size it asked. */
data class ImageRequest(val ref: String, val widthPx: Int, val heightPx: Int)

/**
 * Images for one render that must not wait on the network (a widget update): [instantiator] draws
 * only images already on the device ([ImageResolver.cached]) and records what it missed. A render
 * that misses one fails, so the host draws its fallback, then calls [fetchMissed] off the render
 * path and renders again when it returns true.
 *
 * One per render; not shared between threads.
 */
class CachedImages(private val resolver: ImageResolver) {
  private val missed = LinkedHashSet<ImageRequest>()

  /** What the last instantiation lacked. */
  val misses: Set<ImageRequest> get() = missed

  val instantiator: TemplateInstantiator = TemplateInstantiator { ref, w, h ->
    resolver.cached(ref, w, h) ?: null.also { missed += ImageRequest(ref, w, h) }
  }

  /** Fetches every missed image; true when there were some and all arrived, so a render can draw. */
  suspend fun fetchMissed(): Boolean =
    missed.isNotEmpty() && missed.toList().all { resolver.resolve(it.ref, it.widthPx, it.heightPx) != null }
}
