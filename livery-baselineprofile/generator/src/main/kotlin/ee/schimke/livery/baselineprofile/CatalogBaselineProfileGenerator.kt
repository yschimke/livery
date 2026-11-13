package ee.schimke.livery.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Starts the host and flings its catalog list end to end. Every slot plans, binds, prepares (in the
 * background, rewritten for the display) and plays its template as it scrolls into view, so the
 * profile covers Livery's client, its Compose host and the Remote Compose player on their cold path.
 *
 * Each library filters the shared result (`baselineProfile { filter { … } }` in its build file), so
 * one collection feeds every AAR.
 */
@RunWith(AndroidJUnit4::class)
class CatalogBaselineProfileGenerator {

  @get:Rule val rule = BaselineProfileRule()

  @Test
  fun generate() =
    rule.collect(packageName = "ee.schimke.livery.baselineprofile.host", includeInStartupProfile = true) {
      pressHome()
      startActivityAndWait()
      val list = device.wait(Until.findObject(By.res("catalog")), 15_000) ?: error("catalog list not shown")
      list.setGestureMargin(device.displayWidth / 5)
      // `fling` reports whether the list can still move; cap it so a broken list cannot hang.
      var flings = 0
      while (list.fling(Direction.DOWN) && flings++ < 200) device.waitForIdle()
      list.fling(Direction.UP)
    }
}
