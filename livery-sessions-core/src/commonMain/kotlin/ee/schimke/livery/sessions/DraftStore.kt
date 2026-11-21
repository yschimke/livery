package ee.schimke.livery.sessions

/**
 * Where one kind of session keeps its drafts, and decides how long a session lives. Livery's own
 * `template` kind is one; an app registers its others (a screen's layout, an app's own config).
 * The relay keeps everything around the drafts: codes, presence, the conversation and the decision.
 *
 * A store calls [SessionRelay.changed] with the owner capability when a draft arrives, so a waiting
 * person sees it at once. It may call it from under its own lock: [SessionRelay.changed] takes none.
 */
interface DraftStore {
  /** The session's draft revision, or null once the store no longer holds it (expired, revoked). */
  fun revision(owner: String): Long?

  /** Use the session now, extending its life by the store's rules: seconds left, or null when gone. */
  fun touch(owner: String): Long?

  fun revoke(owner: String): Boolean

  /** A person's comment, for agents that read this store directly rather than the relay. */
  fun note(owner: String, text: String) {}
}
