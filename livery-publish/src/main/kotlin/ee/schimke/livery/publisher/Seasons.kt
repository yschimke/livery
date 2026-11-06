package ee.schimke.livery.publisher

import ee.schimke.livery.protocol.Arm
import ee.schimke.livery.protocol.Conditions
import ee.schimke.livery.protocol.Design
import ee.schimke.livery.protocol.Experiment
import ee.schimke.livery.protocol.InstallPolicy
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.Serializable

/**
 * A seasonal variant, `seasons/<any>.json` in a catalog source tree: while it runs, every slot of
 * a contract it names draws its design instead of the usual one, for everyone the [conditions]
 * match.
 *
 * ```
 * { "id": "winter_2026", "start": "2026-12-01", "end": "2027-01-07",
 *   "designs": { "cadence/headphone-controls@1": "headphone-winter" } }
 * ```
 *
 * A season is a source-tree convenience: the publisher writes it into the manifest as an
 * experiment with one arm ([ARM]) at full allocation (PROTOCOL.md §8.1), so any client that draws
 * design arms draws seasons, and analytics see it as `exp_<id>`. Seasons come before the
 * catalog's own experiments, so a season replaces a design under test while it runs.
 *
 * [start] (inclusive) and [end] (exclusive) are a date (`2026-12-01`, midnight UTC) or an instant
 * with its offset (`2026-12-01T00:00:00+13:00`).
 */
@Serializable
data class Season(
  val id: String,
  val start: String,
  val end: String,
  /** Contract (`id@major`) → the design its slots draw while the season runs. */
  val designs: Map<String, String>,
  val conditions: Conditions = Conditions(),
  val description: String? = null,
)

object Seasons {
  /** The one arm of a season's experiment: everyone the conditions match is in it. */
  const val ARM: String = "on"

  /** Epoch ms of a season's [Season.start] or [Season.end]. */
  fun instant(text: String): Long = runCatching { LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
    .recoverCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
    .recoverCatching { Instant.parse(text).toEpochMilli() }
    .getOrElse { throw IllegalArgumentException("'$text' is neither a date (2026-12-01) nor an instant with an offset (2026-12-01T00:00:00Z)") }

  fun experiment(s: Season): Experiment = Experiment(
    id = s.id,
    arms = listOf(Arm(ARM, 1, s.designs)),
    startEpochMs = instant(s.start),
    endEpochMs = instant(s.end),
    conditions = s.conditions,
    allocationBasisPoints = 10_000,
  )

  /** Problems with [seasons] over [designs]: dates, designs, and two seasons over one contract at once. */
  fun problems(seasons: List<Season>, designs: List<Design>): List<String> = buildList {
    val ranges = mutableListOf<Triple<Season, Long, Long>>()
    for (s in seasons) {
      val start = runCatching { instant(s.start) }.onFailure { add("season ${s.id}: start ${it.message}") }.getOrNull()
      val end = runCatching { instant(s.end) }.onFailure { add("season ${s.id}: end ${it.message}") }.getOrNull()
      if (start != null && end != null) {
        if (end <= start) add("season ${s.id}: ends (${s.end}) before it starts (${s.start})") else ranges += Triple(s, start, end)
      }
      if (s.designs.isEmpty()) add("season ${s.id}: names no design")
      for ((ref, designId) in s.designs) {
        val d = designs.firstOrNull { it.id == designId }
        when {
          d == null -> add("season ${s.id}: unknown design $designId")
          d.contract != ref -> add("season ${s.id}: design $designId is for ${d.contract}, not $ref")
          // Automatic selection never picks a design nobody installed, so it would show to no one.
          d.install != InstallPolicy.Always -> add("season ${s.id}: design $designId is ${d.install}; a season's design must be Always, or it shows only to people who installed it")
        }
      }
    }
    // Two seasons over one contract at once would leave which draws to manifest order.
    for ((i, a) in ranges.withIndex()) for (b in ranges.drop(i + 1)) {
      val shared = a.first.designs.keys.intersect(b.first.designs.keys)
      if (shared.isNotEmpty() && a.second < b.third && b.second < a.third) {
        add("seasons ${a.first.id} and ${b.first.id} overlap for ${shared.sorted()}")
      }
    }
  }
}
