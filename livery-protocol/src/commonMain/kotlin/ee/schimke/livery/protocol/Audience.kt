package ee.schimke.livery.protocol

import kotlinx.serialization.Serializable

/**
 * Who a design is made for (PROTOCOL.md §2.3): the facts of the thing a slot draws that it fits.
 * A design lists any number; one that matches is enough. Facts are the app's own vocabulary
 * (`device.model`, `account.tier`), so Livery knows none of them.
 *
 * [match] gives each fact a pattern, compared without case, where `*` stands for any run of
 * characters. Every fact it names must be in the query's facts and fit its pattern; an empty
 * [match] matches nothing. [priority] ranks audiences the publisher considers narrower (a model
 * over a brand): between two matching designs, the higher [priority] wins, then the larger [match].
 */
@Serializable
data class Audience(val match: Map<String, String>, val priority: Int = 0) {
  /** Whether [facts] fit every pattern [match] sets. */
  fun matches(facts: Map<String, String>): Boolean =
    match.isNotEmpty() &&
      match.all { (name, pattern) -> facts[name]?.let { globMatches(pattern, it) } == true }

  /** How well this audience fits, when it matches [facts]: its [priority], then its size. */
  fun rankFor(facts: Map<String, String>): AudienceRank? =
    if (matches(facts)) AudienceRank(priority, match.size) else null
}

/** How a matching [Audience] ranks: higher [priority] first, then more facts matched. */
data class AudienceRank(val priority: Int, val facts: Int) : Comparable<AudienceRank> {
  override fun compareTo(other: AudienceRank): Int =
    compareValuesBy(this, other, { it.priority }, { it.facts })
}

/**
 * The best rank of [Design.audience] for [facts]: null when none of them matches, so the design is
 * not for this slot. Pickers group designs by it; selection prefers the highest (§5.2).
 */
fun Design.audienceRank(facts: Map<String, String>): AudienceRank? =
  audience.mapNotNull { it.rankFor(facts) }.maxOrNull()

/** [pattern] against [value], ignoring case; `*` matches any run of characters, nothing else is special. */
internal fun globMatches(pattern: String, value: String): Boolean {
  val p = pattern.lowercase()
  val v = value.lowercase()
  val parts = p.split('*')
  if (parts.size == 1) return p == v
  if (!v.startsWith(parts.first()) || !v.endsWith(parts.last())) return false
  if (v.length < parts.first().length + parts.last().length) return false
  var at = parts.first().length
  val end = v.length - parts.last().length
  for (part in parts.subList(1, parts.size - 1)) {
    val found = v.indexOf(part, at)
    if (found < 0 || found + part.length > end) return false
    at = found + part.length
  }
  return true
}
