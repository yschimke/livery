package ee.schimke.livery.benchmark

/**
 * The benchmarks are a T: a spine of the variants that are real choices, run more, and a short bar of the rest.
 *
 * - [Spine]: what an app would pick between (Livery with its defaults and with preparing ahead, the Compose UI twin, the
 *   startup paths, the animated feed). More iterations, so a difference is a difference.
 * - [Completion]: one lane for each thing we do not recommend or have not chosen (an option off, another player, a
 *   prefetch option), so the comparison is complete. Few iterations: it says whether a lane is wildly off, not by how much.
 * - Neither: sweeps for working out a value (window sizes, planning costs). Not in the routine run; run them by name.
 *
 * Run a tier with the runner argument `annotation`, e.g. `-e annotation ee.schimke.livery.benchmark.Spine` (see
 * `livery-baselineprofile/README.md`); `Spine,Completion` is the routine run.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Spine

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Completion

/** How many times a lane is measured, and how much it does each time. */
internal enum class Tier(val iterations: Int, val flings: Int, val startupIterations: Int, val changeIterations: Int, val taps: Int) {
  // A cold start is a few seconds, so the spine can afford many of them; a fling of the feed is half a minute.
  Spine(iterations = 6, flings = 4, startupIterations = 10, changeIterations = 8, taps = 10),
  Completion(iterations = 3, flings = 3, startupIterations = 3, changeIterations = 3, taps = 6),
}
