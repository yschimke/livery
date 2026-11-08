# 03 — Public publication and preview hosting

Read [coordination](README.md). **Goal:** turn a selected artifact/revision into a stable,
publicly fetchable preview with metadata and an admitted native-host descriptor.

## Repositories and existing code

Livery: `livery-publish`, `livery-render`,
`livery-uibuilder/.../UiBuilderClient.kt` (authenticated, revision-selected export).
Hosting: `compose-preview-server/server/src/main/kotlin/ee/schimke/composeai/cli/serve/`.
Inspect `ServeUiBuilderRoutes.kt`, `ServeUiBuilderPublicReaders.kt`,
`ServeUiBuilderThumbnails.kt`, `ServeSocialCard.kt`, `ServeSiteIndex.kt` and existing
unfurl generation in `ServeWeb.kt`. Existing tests include
`ServeUiBuilderHistoryAndUnfurlTest`, `ServeUiBuilderPublicDesignIntegrationTest` and
`ServeUiBuilderPublicReadersTest`. Public-design/unfurl support already exists: reuse it
where appropriate rather than adding a parallel visibility model.

## Deliver

1. Agree 00's publish/resolve contract and exact public routes. Inspect current permissions:
   publication requires author access but recipients need no editor login.
2. Publish selected RC revision, public example inputs, assets, contract/catalog references
   and static thumbnail. Accept CLI/CI artifacts from 01 as well as UI Builder exports.
   Explicitly copied public artifacts must not expose source history, collaborator details,
   export bearer tokens or editor sessions.
3. Persist immutable publication identity and reuse completed publication on retry. Return a
   shareable URL only after required assets/metadata are ready; make failures retryable and
   reportable to 04. Do not silently change which revision a pinned URL resolves to.
4. Serve HTML title/description/canonical/OpenGraph metadata without JS, public image GET/HEAD,
   descriptor, cache validators and correct content types. Integrate 02's versioned viewer.
5. Specify browser CORS and framing/CSP for 05's iframe, while preserving existing private
   design rules. Define retention, expiry/revocation and useful withdrawn-publication fallback.
6. Provide local/integration server fixtures for 04/05/07/09; deployment is a later delivery step.

## Ownership and acceptance

Own server DTOs/route registration/storage. 04 owns editor controls; 02 owns viewer assets.
Use a trusted publisher configuration and bind descriptor/resource origins to publication
policy. Do not enable arbitrary runtime code or sign arbitrary unvalidated inputs.

Prove anonymous view/unfurl works, private editor content stays private, chosen revision
survives later edits, partial publish does not leak a broken share, and retry reuses identity.
Verify caching does not confuse revisions or expose authenticated responses publicly.
Return exact HTTP examples, local run instructions, checks and publication lifecycle decisions.
