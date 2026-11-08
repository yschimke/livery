# 07 — Heron native RC external-link previews

Read [coordination](README.md). **Goal:** an ordinary external-link post pointing at the
supported preview origin becomes a native Compose RC preview, with normal link fallback.

## Source and entry points

Inspect current `tunjid/heron` and its AGENTS.md; baseline was
`dfe4ba961a825fe23d6a788c1edcc9d75dd4f121`.
Relevant paths:
- `ui/timeline/src/commonMain/kotlin/com/tunjid/heron/timeline/ui/post/PostEmbed.kt`
- sibling `PostExternal.kt` and its noninteractive composer-preview caller
- `data/models/.../ExternalEmbed.kt` and the repository/external record resolution layer
- Livery `livery-compose/.../LiveryTemplate.kt`

Start with a dependency/target compatibility spike. Inspected Compose versions differed:
Livery 1.12.2 versus Heron 1.13.0-beta01. Verify actual current runtime compatibility,
published artifact availability and effects on Heron's other targets.

## Deliver

1. An opt-in, origin/path-allowlisted resolver consuming 00/01 fixtures through the data layer.
   Keep network and repository work outside composition; cache prepared plans/data.
2. Add a native presentation branch at external-link rendering. Retain title/thumb/link during
   loading, trust rejection, unsupported capability and failure. Never render arbitrary RC
   simply because a URL or MIME type claims support.
3. Embed `livery-compose`; apply host/theme/size/capability limits. Use existing binding,
   selection and action gates. Configure publisher trust and constrain descriptor resolution.
4. Add the explicit preview action adapter: local bookmark demonstration/reset and open-event
   handling. Do not treat a Bluesky login as permission to bookmark a Confetti session.
   08 later provides real-resource action policy.
5. Preserve moderation and noninteractive composer previews. Hidden/blurred content must not
   activate subscriptions/actions. A child button must not also navigate the enclosing card.
6. Tie player work/data observation to visibility and lifecycle. Separate reusable artifact
   caches from local per-preview interaction state. Dispose correctly during list reuse.

## Acceptance and handoff

Test actual Android rendering with the Confetti artifact/fonts, two appearances with independent
preview state, long inputs, narrow widths, scroll/reuse, background/resume, trust failure,
missing bytes and ordinary fallback. Verify app responsiveness and non-Android build fallback.
The first test can use an existing/pasted ordinary URL; incoming share stream 06 is not a blocker.

Return feature flag, dependency versions, screenshots/interaction evidence, capability limits,
tests and resolver contract feedback. Own timeline/repository RC code; coordinate any shared
navigation/DI registration with 06. Do not modify generated Bluesky Lexicon unions.
