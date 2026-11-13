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
 * slots, the Remote Compose player alone, and the same designs written in Compose UI. Both are compiled with the shipped baseline
 * profiles, as an app installed from a store would be. FrameTimingMetric's frame durations and
 * overruns for the two tests are the comparison; on an emulator they are only a rough one.
 */
@RunWith(AndroidJUnit4::class)
class FeedScrollBenchmark {
  @get:Rule val rule = MacrobenchmarkRule()

  /** Livery slots: planning, binding, events and the player, as an app's feed runs them. */
  @Test fun livery() = fling("livery")

  /** The same prepared documents and values, played by the player with nothing of Livery around it. */
  @Test fun rcPlayer() = fling("rc")

  /** The same designs written in Compose UI, drawing the same pixels (TwinRenderParityTest). */
  @Test fun composeUi() = fling("compose")

  private fun fling(mode: String) =
    rule.measureRepeated(
      packageName = PACKAGE,
      metrics = listOf(FrameTimingMetric()),
      compilationMode = CompilationMode.Partial(),
      startupMode = StartupMode.WARM,
      iterations = 5,
      setupBlock = {
        pressHome()
        startFeed(mode)
      },
    ) {
      val feed = device.findObject(By.res("feed"))
      feed.setGestureMargin(device.displayWidth / 5)
      repeat(FLINGS) {
        feed.fling(Direction.DOWN)
        device.waitForIdle()
      }
    }

  private fun MacrobenchmarkScope.startFeed(mode: String) {
    startActivityAndWait(
      Intent().setClassName(PACKAGE, "$PACKAGE.FeedActivity").putExtra("mode", mode)
    )
    device.wait(Until.hasObject(By.res("feed")), 15_000)
  }

  private companion object {
    const val PACKAGE = "ee.schimke.livery.baselineprofile.host"
    const val FLINGS = 6
  }
}
