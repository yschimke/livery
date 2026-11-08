title: The web
order: 17

# The web

> **Planned.**

**In one line:** a `<livery-template>` element that fetches, verifies and plays a template with
rc-players' TypeScript player, fed by the same catalogs.

## The plan

- A TypeScript client (`fetch`, `crypto.subtle` Ed25519, Cache API storage) ported from the Kotlin
  one and checked against the shared conformance fixtures.
- A web component over `remote-compose-player-js`.

Today, the Kotlin client already compiles for Wasm (Ktor's `fetch` engine), and `livery-compose`
runs in the browser through Compose Multiplatform.

**Hosting for the web:** GitHub release downloads send no CORS headers, so a browser must read a
catalog from GitHub Pages, a CDN or a design server instead ([Hosting](../hosting.md)).

### npm releases

`@livery/a2ui` follows the repository version. Release-please updates its package and lockfile;
merging the release PR builds and checks the npm tarball, then publishes it alongside Maven.
The repository owner must configure `NPM_TOKEN` with publish access to `@livery/a2ui` before the
first npm release. No npm release has been made yet. `npm run pack:check` checks the files and
relative runtime imports in the actual tarball in CI. The TypeScript player remains vendored
with its provenance until rc-players publishes a supported npm package.

### React and Angular

The same session and controller power all three web renderers. Use
`liveryReactCatalog(session, options)` from `@livery/a2ui/react` with React's `A2uiSurface`, or
`liveryAngularCatalog(session, options)` from `@livery/a2ui/angular` with Angular's
`provideA2Ui({ catalogs: [catalog] })` and `SurfaceComponent`. Install the matching optional peers:
React 18/19 with `@a2ui/react` 0.15; Angular 21 with `@a2ui/angular` 0.14 (which uses web_core 0.15).
Angular applications also configure A2UI's `provideMarkdownRenderer()` for the basic components.
The Angular entry ships partial compilation for the application's Angular linker.

`options.base` extends the selected renderer's catalog. Actions, data-model paths, themes,
decision ids, retry and height behavior are shared. `fallback` returns a Lit template for Lit,
a React node for React, or a DOM node/string for Angular. `npm test` runs the same sample catalog
and all interaction checks once per renderer; `CHROMIUM_PATH` can select a local Chromium binary.

### Persistent browser cache

Web sessions default to IndexedDB for blobs and small named documents. A warm session reads
its cached catalog immediately and refreshes in the background, so template rendering does not
wait for a network response. The existing repository still owns expiry, stale grace, install
policy, blob eviction and image-cache metadata. Browser storage can be cleared or evicted by
the browser; this is a cache, not durable application data.

Set `persistentCache: false` in `LiverySession.open` for memory-only sessions (also useful in
hosts that deny IndexedDB). Cache namespaces include the catalog and trust configuration:
switching from an unsigned development catalog to signed trust cannot reuse the old manifest.
The browser suite reloads with all catalog requests blocked and checks that cached templates
draw, that memory-only sessions are empty offline, and that a different trust configuration
cannot inherit the cached unsigned catalog. Serving an application's shell offline remains the
host application's responsibility.

### Wasm distribution size

`webDist` runs Kotlin's pinned Binaryen optimizer before packaging. On the 0.6.0 baseline this
reduces raw Wasm from 4,749,595 to 1,454,082 bytes (69.4%). Ktor remains in the module to preserve
its fetch and redirect contract. `docs/WASM-SIZE.md` records section and dependency measurements;
`scripts/wasm-size.py` reproduces the breakdown for subsequent builds.
