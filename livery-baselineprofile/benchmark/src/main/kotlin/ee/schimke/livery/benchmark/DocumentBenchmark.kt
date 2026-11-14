package ee.schimke.livery.benchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One template filling the screen (`DocumentActivity`, the sample catalog's `poll-card`), in each
 * lane the host offers: Livery with its defaults, Livery preparing in composition, the player alone,
 * and the Compose UI twin.
 *
 * - `*Startup`: cold process start to the first frame with the document in it (`StartupTimingMetric`: the
 *   activity reports fully drawn after that frame, so `timeToFullDisplayMs` is the number; the initial
 *   display time can stop earlier, at an empty frame).
 * - `*Change`: tapping the document pushes new data; `FrameTimingMetric` over those taps.
 *   The first tap and later ones are not separated yet (see docs/benchmarks/PLAN.md).
 *
 * On an emulator these are only rough: graphics are software-rendered, so lanes are not told apart in
 * milliseconds. Timings belong to a real device.
 */
@RunWith(AndroidJUnit4::class)
class DocumentBenchmark {
  @get:Rule val rule = MacrobenchmarkRule()

  @Test fun liveryStartup() = startup("livery")
  @Test fun liveryInCompositionStartup() = startup("livery-incomposition")
  @Test fun rcPlayerStartup() = startup("rc")
  @Test fun composeUiStartup() = startup("compose")

  @Test fun liveryChange() = change("livery")
  @Test fun rcPlayerChange() = change("rc")
  @Test fun composeUiChange() = change("compose")

  private fun startup(lane: String) = measure(lane, listOf(StartupTimingMetric()), StartupMode.COLD) {}

  private fun change(lane: String) = measure(lane, listOf(FrameTimingMetric()), StartupMode.WARM) {
    repeat(TAPS) {
      device.findObject(By.res("document")).click()
      device.waitForIdle()
    }
  }

  private fun measure(lane: String, metrics: List<Metric>, mode: StartupMode, block: MacrobenchmarkScope.() -> Unit) =
    rule.measureRepeated(
      packageName = PACKAGE,
      metrics = metrics,
      compilationMode = CompilationMode.Partial(),
      startupMode = mode,
      iterations = 5,
      setupBlock = { if (mode == StartupMode.WARM) { pressHome(); open(lane) } },
    ) {
      if (mode == StartupMode.COLD) open(lane)
      block()
    }

  private fun MacrobenchmarkScope.open(lane: String) {
    startActivityAndWait(Intent().setClassName(PACKAGE, "$PACKAGE.DocumentActivity").putExtra("lane", lane))
    device.wait(Until.hasObject(By.res("document")), 15_000)
  }

  private companion object {
    const val PACKAGE = "ee.schimke.livery.baselineprofile.host"
    const val TAPS = 10
  }
}
