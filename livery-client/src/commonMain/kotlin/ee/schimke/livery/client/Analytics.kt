package ee.schimke.livery.client

import ee.schimke.livery.protocol.TemplateEvent

/**
 * An analytics pipeline: Firebase Analytics (`livery-firebase`), Amplitude, Segment, a warehouse
 * loader, or the app's own. It gets flat, named events in [AnalyticsEvents]' shape, so one mapping
 * works everywhere. Implementations must not block: queue, and send off the caller's thread.
 */
fun interface AnalyticsSink {
  fun log(name: String, params: Map<String, Any>)
}

/**
 * The analytics shape of a [TemplateEvent]: `livery_impression`, `livery_viewable`,
 * `livery_action`, `livery_dismiss`, `livery_fallback`, `livery_error` and `livery_exposure`, with
 * `placement`, `decision_id`, `feed_item_id`, `design_id`, `template` (the first 12 hex of its
 * sha256), `action`, `detail`, and one `exp_<experiment>` per experiment arm. Names and values
 * stay inside the common limits (Firebase's: 40-character names, 100-character values, 25 params).
 */
object AnalyticsEvents {
  const val PREFIX: String = "livery_"
  private const val MAX_NAME = 40
  private const val MAX_VALUE = 100
  private const val MAX_PARAMS = 25

  fun name(type: TemplateEvent.Type, prefix: String = PREFIX): String = (prefix + type.name.lowercase()).take(MAX_NAME)

  fun params(e: TemplateEvent): Map<String, Any> = buildMap {
    e.placement?.let { put("placement", it.take(MAX_VALUE)) }
    e.decisionId?.let { put("decision_id", it.take(MAX_VALUE)) }
    e.feedItemId?.let { put("feed_item_id", it.take(MAX_VALUE)) }
    e.designId?.let { put("design_id", it.take(MAX_VALUE)) }
    e.sha256?.let { put("template", it.take(12)) }
    e.action?.let { put("action", it.take(MAX_VALUE)) }
    e.detail?.let { put("detail", it.take(MAX_VALUE)) }
    for ((experiment, arm) in e.experiments.toList().sortedBy { it.first }) {
      if (size >= MAX_PARAMS) break
      put(paramName("exp_$experiment"), arm.take(MAX_VALUE))
    }
  }

  /** A parameter name analytics backends accept: letters, digits and `_`, starting with a letter, at most 40. */
  fun paramName(raw: String): String {
    val cleaned = raw.map { if (it.isLetterOrDigit() && it.code < 128) it.lowercaseChar() else '_' }.joinToString("")
    return (if (cleaned.firstOrNull()?.isLetter() == true) cleaned else "p_$cleaned").take(MAX_NAME)
  }
}

/** Sends every [TemplateEvent] to these analytics pipelines; one that throws doesn't stop the others. */
class AnalyticsEventSink(
  private val sinks: List<AnalyticsSink>,
  private val prefix: String = AnalyticsEvents.PREFIX,
) : EventSink {
  constructor(vararg sinks: AnalyticsSink) : this(sinks.toList())

  override fun emit(event: TemplateEvent) {
    val name = AnalyticsEvents.name(event.type, prefix)
    val params = AnalyticsEvents.params(event)
    for (s in sinks) runCatching { s.log(name, params) }
  }
}

/** One [EventSink] that hands every event to each of [sinks] (analytics, logging, a test probe). */
fun eventSinks(vararg sinks: EventSink): EventSink = EventSink { e -> for (s in sinks) runCatching { s.emit(e) } }
