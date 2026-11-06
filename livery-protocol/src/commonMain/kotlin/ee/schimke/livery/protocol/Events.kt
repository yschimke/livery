package ee.schimke.livery.protocol

import kotlinx.serialization.Serializable

/** What the client reports about a rendered template; an app routes them to its own analytics. */
@Serializable
data class TemplateEvent(
  val type: Type,
  val atEpochMs: Long,
  val placement: String? = null,
  val decisionId: String? = null,
  val feedItemId: String? = null,
  val designId: String? = null,
  val sha256: String? = null,
  val action: String? = null,
  val detail: String? = null,
  /** Experiment → arm for every experiment that shaped what drew (a decision's, a design's). */
  val experiments: Map<String, String> = emptyMap(),
  /** An [Type.Action]'s arguments that its contract declares (PROTOCOL.md §7): never any other. */
  val args: Map<String, String> = emptyMap(),
  /** A [Type.Leave]'s time on screen: milliseconds at least half visible while it was shown. */
  val durationMs: Long? = null,
) {
  @Serializable
  enum class Type {
    /** The template's first frame was drawn. */
    Impression,
    /** At least half of it stayed on screen for a second. */
    Viewable,
    Action,
    Dismiss,
    /** The chosen template couldn't be played; [detail] says which fallback drew instead. */
    Fallback,
    Error,
    /** The user was shown an experiment's arm: what A/B analysis counts ([experiments] says which). */
    Exposure,
    /** The template left the screen (or the slot showed something else); [durationMs] is how long it was in view. */
    Leave,
  }
}
