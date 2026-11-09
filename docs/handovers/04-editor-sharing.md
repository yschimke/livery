# 04 — UI Builder publication and share controls

Read [coordination](README.md). **Goal:** a component page leads to a public preview and a
user-reviewed Bluesky post through ordinary sharing.

## Starting points and boundaries

Read `compose-ui-builder/AGENTS.md`; inspect `ui-builder/src/` for editor actions and
platform/browser bridges, and `ui-builder-export`/`ui-builder-runtime` for revision/export
semantics. `ui-builder-web` is a packaging-only module, not the editor implementation.
The hosting service lives in `compose-preview-server`; 03 owns its publication API.
Start against a fake publication response while that service is being built.

## Deliver the flow

1. Choose component, revision and public example/theme/framing. Show the intended public
   result before explicit publication. Keep save/edit and publish distinct.
2. Display publishing, failure/retry and ready states; handle edits occurring while publishing
   without accidentally substituting a later revision. Reuse 03's completed publication.
3. Offer Share to Bluesky, system Share where available, and Copy link. Encode the suggested
   text plus URL through `https://bsky.app/intent/compose?text=...`.
4. Invoke browser sharing from a fresh user gesture after publication completes; a long async
   export can consume transient activation and block popups/share dialogs. Handle unsupported
   Web Share, cancellation and clipboard failure. Share text/URL, not RC binary attachments.
5. Keep the selected app responsible for authentication, card creation, review and Post.
   Never infer “posted” from closing a share sheet. No Bluesky token or direct posting API.
6. Test desktop and Android; the OS chooses available share targets. The Bluesky intent does
   not guarantee it launches Heron. Include Copy link as a reliable path.

## Acceptance and handoff

UI covers publication failure, stale selected revision, double-click/retry, no clipboard,
cancelled share and signed-out recipient. Inspect shared URL/text for credentials.
Do not expose implementation details as required user decisions.
Provide screenshots plus a browser interaction recording against a mock, then 03's local
server. Keep imported RC preview and editable UI Builder document capabilities distinct;
Kotlin/CI publication remains valid without an editable Confetti import.
