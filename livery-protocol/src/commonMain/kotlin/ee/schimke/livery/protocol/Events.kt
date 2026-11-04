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
  }
}
