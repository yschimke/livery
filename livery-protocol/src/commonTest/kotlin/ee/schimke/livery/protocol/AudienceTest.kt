package ee.schimke.livery.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudienceTest {
  private val printer =
    mapOf("device.integration" to "acme", "device.manufacturer" to "Acme Corp", "device.model" to "X1 Carbon")

  @Test
  fun everyNamedFactMustFitItsPatternWithoutCase() {
    assertTrue(Audience(mapOf("device.integration" to "ACME")).matches(printer))
    assertTrue(Audience(mapOf("device.integration" to "acme", "device.model" to "x1*")).matches(printer))
    assertFalse(Audience(mapOf("device.integration" to "acme", "device.model" to "P1*")).matches(printer))
    assertFalse(Audience(mapOf("device.room" to "*")).matches(printer), "a fact the query lacks never fits")
  }

  @Test
  fun anEmptyAudienceMatchesNothing() {
    assertFalse(Audience(emptyMap()).matches(printer))
  }

  @Test
  fun aStarMatchesAnyRunAndNothingElseIsSpecial() {
    fun fits(pattern: String, value: String) = globMatches(pattern, value)
    assertTrue(fits("x1*", "X1 Carbon"))
    assertTrue(fits("*carbon", "X1 Carbon"))
    assertTrue(fits("*1 C*", "X1 Carbon"))
    assertTrue(fits("x*1*n", "X1 Carbon"))
    assertTrue(fits("*", ""))
    assertTrue(fits("ab*ab", "abab"))
    assertFalse(fits("ab*ab", "aba"), "the prefix and suffix may not overlap")
    assertFalse(fits("x1", "X1 Carbon"), "without a star the whole value must match")
    assertFalse(fits("x1.carbon", "X1 Carbon"), "a dot is literal")
    assertFalse(fits("x*z*n", "X1 Carbon"))
  }

  @Test
  fun aDesignRanksByItsBestMatchingAudiencePriorityThenSize() {
    val design =
      Design(
        "d", "c@1", "D",
        audience =
          listOf(
            Audience(mapOf("device.integration" to "acme", "device.manufacturer" to "acme*"), priority = 2),
            Audience(mapOf("device.model" to "X1*"), priority = 3),
            Audience(mapOf("device.model" to "P1*"), priority = 9),
          ),
      )
    assertEquals(AudienceRank(3, 1), design.audienceRank(printer))
    assertEquals(AudienceRank(2, 2), design.audienceRank(printer - "device.model"))
    assertNull(design.audienceRank(mapOf("device.integration" to "other")))
    assertNull(Design("e", "c@1", "E").audienceRank(printer), "a design for everyone has no audience rank")
    assertTrue(AudienceRank(3, 1) > AudienceRank(2, 5))
    assertTrue(AudienceRank(2, 3) > AudienceRank(2, 2))
  }
}
