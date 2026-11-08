# 01 — Confetti artifact and native reference

**On hold:** wait for the relevant Confetti PRs to land. Do not begin implementation or
continue draft-stack investigation until then. Reinspect merged code and repository instructions;
the source references below are historical context, not current integration guarantees.

Read [coordination](README.md). **Goal:** supply one real non-shader KotlinConf session card,
its dependencies and native reference behaviour for every consumer.

## Source and starting points

Draft stack: [#2008](https://github.com/joreilly/Confetti/pull/2008) through
[#2013](https://github.com/joreilly/Confetti/pull/2013). Inspected #2012 head:
`yschimke/Confetti@8a7fb831c40ad5a504796025b9fb42c1625011c4`.
Recheck upstream status and base the work on the appropriate stack layer; do not assume merged.
Confetti is not checked out in this workspace; obtain an isolated checkout when assigned.

Inspect `livery-catalog/README.md`, `livery-catalog/catalogs/kotlinconf/`,
`livery-contracts/`, `livery/.../ConferenceSlots.kt` and its component tests.
The existing session contract reads title/speakers/time/room/live/bookmarked and emits
`app:open-session` and `app:toggle-bookmark`. It does not contain session identity.

## Deliver

1. Pin source commit, design ID, contract and RC hash; package the actual RC, Poppins fonts,
   licensing/attribution, theme values and a default/long example. Keep Confetti ownership.
2. Build/inspect the catalog and produce deterministic reference renders at agreed sizes,
   light/dark themes and normal/long text. Show the real Android `sduiDebug` path too.
3. Document capabilities including profile/API, fonts and minimum width. Existing `in-app`
   output does not prove web compatibility. Work with 02 on one artifact before proposing
   separately exported targets; report divergence honestly.
4. Specify a tiny preview adapter: bookmark toggles a local example flag, open reports a
   preview event. No account/network writes. Supply an action/state trace to 00/02/07.
5. Provide a CLI/CI publication input for 03. Kotlin-authored capture must work without
   assuming an editable UI Builder round-trip.

## Checks and limits

Use current repo instructions and targeted catalog/contract/native checks. Existing starting
commands include `:livery-catalog:test`, `:livery-contracts:jvmTest`,
`:livery:testDebugUnitTest`, `:livery-catalog:buildLiveryChannels`.
Confirm current task names before running; native device evidence is distinct from Robolectric.

Do not add shaders, change production defaults, promote unsigned debug catalogs to trusted
publications, or rewrite Confetti's app contract for convenience. Coordinate shared contract
changes with 08. Supply the bundle/manifest, reference images, action trace and exact commands.
