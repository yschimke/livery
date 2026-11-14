package ee.schimke.livery.benchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The host's feed of sample cards (`FeedActivity`), flung top to bottom, drawn three ways: Livery
 * slots, the Remote Compose player alone, and the same designs written in Compose UI; and Livery and
 * Compose UI again with each of Compose's prefetch options (a cache window, pausable composition). Both are compiled with the shipped baseline
 * profiles, as an app installed from a store would be. FrameTimingMetric's frame durations and
 * overruns for the two tests are the comparison; on an emulator they are only a rough one.
 */
@RunWith(AndroidJUnit4::class)
class FeedScrollBenchmark {
  @get:Rule val rule = MacrobenchmarkRule()

  /** Livery slots: planning, binding, events and the player, as an app's feed runs them. */
  @Test fun livery() = fling("livery")

  /** Livery without the deferred lookahead pass (and so without flat layout): what the deferral saves. */
  @Test fun liveryNoDefer() = fling("livery-nodefer")

  /** Livery with deferral but one composable per component: what flat layout saves. */
  @Test fun liveryNoFlat() = fling("livery-noflat")

  /** The same prepared documents and values, played by the player with nothing of Livery around it. */
  @Test fun rcPlayer() = fling("rc")

  /** The same designs written in Compose UI, drawing the same pixels (TwinRenderParityTest). */
  @Test fun composeUi() = fling("compose")

  /**
   * Compose's own prefetch options, each for Livery and for the twin, so it is measured as a
   * difference to each (FeedActivity): a cache window of one viewport ahead and half behind, and
   * pausable composition in prefetch.
   */
  @Test fun liveryCacheWindow() = fling("livery", Options.CacheWindow)

  @Test fun composeUiCacheWindow() = fling("compose", Options.CacheWindow)

  @Test fun liveryPausable() = fling("livery", Options.Pausable)

  @Test fun composeUiPausable() = fling("compose", Options.Pausable)

  private enum class Options(val apply: Intent.() -> Unit) {
    None({}),
    CacheWindow({ putExtra("cacheAhead", 1f).putExtra("cacheBehind", 0.5f) }),
    Pausable({ putExtra("pausable", true) }),
  }

  private fun fling(mode: String, options: Options = Options.None) =
    rule.measureRepeated(
      packageName = PACKAGE,
      metrics = listOf(FrameTimingMetric()),
      compilationMode = CompilationMode.Partial(),
      startupMode = StartupMode.WARM,
      iterations = 5,
      setupBlock = {
        pressHome()
        startFeed(mode, options)
      },
    ) {
      val feed = device.findObject(By.res("feed"))
      feed.setGestureMargin(device.displayWidth / 5)
      repeat(FLINGS) {
        feed.fling(Direction.DOWN)
        device.waitForIdle()
      }
    }

  private fun MacrobenchmarkScope.startFeed(mode: String, options: Options) {
    startActivityAndWait(
      Intent().setClassName(PACKAGE, "$PACKAGE.FeedActivity").putExtra("mode", mode).apply(options.apply)
    )
    device.wait(Until.hasObject(By.res("feed")), 15_000)
  }

  private companion object {
    const val PACKAGE = "ee.schimke.livery.baselineprofile.host"
    const val FLINGS = 6
  }
}
