# 08 — Public Confetti session identity, updates and sharing

**On hold:** wait for the relevant Confetti PRs to land. Do not begin implementation or
continue draft-stack investigation until then. Reinspect merged code and repository instructions;
the source references below are historical context, not current integration guarantees.

Read [coordination](README.md). **Goal:** extend a component preview into a useful real
session share without exporting private account state or inventing another agenda backend.
This is the second product milestone; source/data discovery can start immediately.

## Starting evidence

Use the Confetti draft stack and source baseline recorded in 01. In
`livery/.../ConferenceSlots.kt`, `SessionDetails.toSessionCard` produces display inputs;
`SessionCardModel` supplies session identity and callbacks around that data.
`confetti/session@1` contains display values, not conference/session IDs.
Find the current GraphQL/public data API and app/web deep-link implementation in source;
do not infer routes from project branding.

## Deliver

1. Document stable conference and session identifiers, publicly readable data, refresh/cache
   behaviour and canonical public URL. Verify a real session can be fetched/opened signed out.
   Confirm how identifiers survive schedule edits and unavailable/removed sessions.
2. An app-owned adapter translating public data into the existing display contract through
   Livery's normal binder. Include timezone-aware structured timing to calculate “live”;
   the formatted time string alone is insufficient.
3. Carry the public resource reference in 00's descriptor. Reuse the published design without
   copying the author's bookmarks, credentials or other private app state.
4. Define per-host actions: verified session link with browser fallback; real bookmark opens
   Confetti or uses a future authorised integration. In preview mode keep actions explicitly
   simulated. Unsupported hosts must not pretend an account write succeeded.
5. Add a Confetti session Share affordance if current UI lacks a usable one, reusing existing
   platform share plumbing and 03's public URL. Preserve ordinary session links when publication
   is unavailable. Coordinate with 01 rather than editing the same catalog/component files.
6. Start updates with conditional fetches while observed, not mandatory streaming. Reconnect
   fetches current data; stale UI is clear; shared read caches never share personal state.

## Acceptance

A signed-out browser and Heron resolve the same public session and show updated time/room
after a controlled fixture change. Reposting does not duplicate sessions or reset bookmarks.
A missing session and offline read have useful fallback; an open action resolves to the right
conference/session; no account write occurs without its host's authorised flow.

Return API/route evidence, fixtures, mapping code, link behaviour on installed/uninstalled app,
tests and limits. Actual public session data is separate from publication design revisions.
No changes to general Livery core, new poll backend, or AT account scopes are needed.
