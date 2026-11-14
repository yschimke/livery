package ee.schimke.livery.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrepareWindowTest {
  @Test fun aheadOfTheLastVisibleItem() = assertEquals(0..11, prepareWindow(first = 0, last = 3, count = 100, ahead = 8, behind = 0))

  @Test fun behindTheFirstVisibleItemToo() = assertEquals(5..21, prepareWindow(first = 10, last = 13, count = 100, ahead = 8, behind = 5))

  @Test fun clippedToTheItemsThereAre() = assertEquals(0..4, prepareWindow(first = 0, last = 2, count = 5, ahead = 8, behind = 3))

  @Test fun clippedAtTheStart() = assertEquals(0..9, prepareWindow(first = 1, last = 1, count = 100, ahead = 8, behind = 4))

  @Test fun nothingWhenThereAreNoItems() = assertTrue(prepareWindow(first = 0, last = 0, count = 0, ahead = 8, behind = 0).isEmpty())

  @Test fun beforeTheListHasLaidOutItStartsAtTheTop() = assertEquals(0..8, prepareWindow(first = 0, last = 0, count = 50, ahead = 8, behind = 0))

  @Test fun aWindowPastTheEndIsEmpty() = assertTrue(prepareWindow(first = 20, last = 22, count = 10, ahead = 8, behind = 0).isEmpty())
}
