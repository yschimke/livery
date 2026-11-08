# 06 — Heron incoming text share to post composer

Read [coordination](README.md). **Goal:** share a normal preview URL from a browser into
Heron's editable composer. This can be implemented and tested without Remote Compose.

## Evidence and first inspection

Inspected upstream: `tunjid/heron@dfe4ba961a825fe23d6a788c1edcc9d75dd4f121`.
Recheck current source and AGENTS.md in an isolated checkout.
Inspect `apps/androidApp/src/main/AndroidManifest.xml`, `MainActivity.kt`,
`feature/compose`, navigation/auth continuation, and existing external-link creation.

At the inspected revision, the app declares VIEW links but no SEND text target;
`MainActivity` reads `intent.data`, not `EXTRA_TEXT`. Confirm merged manifests and current
navigation before changing anything. VIEW registration alone does not prove compose-intent
routing works.

## Deliver

1. Receive standard Android SEND text/plain, normalize title/text/URL and present it as a
   draft using the existing composer. Do not auto-post or add RC-specific payload handling.
2. Handle cold start, warm activity delivery and recreation exactly once, following current
   activity launch mode and navigation patterns. Consume delivered content without losing it
   before login or duplicating drafts on rotation/recomposition.
3. Preserve the text through required sign-in/account selection, with cancellation semantics.
   Keep URL metadata fetching in Heron's existing data layer.
4. Verify whether `/intent/compose?text=...` VIEW links are supported. Add only if appropriate
   for the existing route model; do not make this a prerequisite for OS sharing.
5. Keep ordinary pasted URLs and existing deep links working. Host renderer 07 is independent;
   this feature should remain useful when 07 is absent.

## Acceptance

Exercise browser share -> choose Heron -> edit text -> normal link preview -> composer.
Use fixture/stub submission to test the post boundary unless a real test post is separately
authorised. Opening a composer is not proof of publication. Check signed-out continuation,
cancel, duplicate intents, long/Unicode text, malformed URLs and process recreation.

Own Android entry handling and compose routing. Coordinate shared DI/navigation edits with 07,
which owns timeline presentation. Maintain non-Android builds. Return route/intent examples,
automated checks, device evidence and the remaining real-post validation steps.
