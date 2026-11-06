package ee.schimke.livery.firebase

import ee.schimke.livery.client.ImpressionRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FirestoreIdsTest {
  private fun id(rule: String, device: String = "phone", at: Long = 1, seq: Int = 0) = impressionDocumentId(ImpressionRecord(rule, at, device, seq))

  @Test fun differentRecordsNeverShareADocument() {
    assertNotEquals(id("promo/a"), id("promo_a"))
    assertNotEquals(id("a", device = "x-1", at = 2), id("1-2-0-a", device = "x"))
    assertNotEquals(id("a", seq = 0), id("a", seq = 1))
  }

  @Test fun theSameRecordAlwaysHasTheSameDocument() {
    assertEquals(id("promo/a"), id("promo/a"))
    assertEquals(64, id("promo/a").length)
  }
}
