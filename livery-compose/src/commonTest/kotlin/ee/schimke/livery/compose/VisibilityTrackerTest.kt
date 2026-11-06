package ee.schimke.livery.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisibilityTrackerTest {
  @Test fun timeInViewAddsUpAcrossVisits() {
    val t = VisibilityTracker()
    assertEquals(0, t.inViewMs(100), "never shown")
    t.visible(true, 1_000)
    assertTrue(t.isVisible)
    assertEquals(500, t.inViewMs(1_500), "while in view")
    t.visible(false, 2_000)
    t.visible(false, 2_500)
    assertFalse(t.isVisible)
    assertEquals(1_000, t.inViewMs(9_000), "out of view adds nothing")
    t.visible(true, 10_000)
    t.visible(true, 10_200)
    assertEquals(1_500, t.inViewMs(10_500), "a repeated callback doesn't restart the span")
  }
}
