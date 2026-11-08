title: The web
order: 17

# The web

`@livery/a2ui` renders Livery contracts in A2UI's Lit, React and Angular surfaces. The common
Kotlin pipeline, compiled to Wasm, selects and verifies templates and gates actions. The
TypeScript Remote Compose player draws them on a canvas and updates named values live.
An optional `player: 'cmp'` uses the in-page Compose Multiplatform player; it is prepared before
catalog registration and supports the same framework adapters. CMP keeps authored height when
root measurement is unavailable. Its runtime and bundled fonts increase download and viewport
costs; see `docs/A2UI-CMP.md` for the measured tradeoffs.
The package is built; its first npm publication needs the repository owner's publish token.

Use `LiverySession.open({ catalog, keys })`, then `liveryCatalog(session, { catalogId })` for Lit,
or one of the framework adapters below. Outside A2UI, a standalone `<livery-template>` element
is still **Planned**. `livery-compose` also runs in browsers through Compose Multiplatform.

The host's `--livery-a2ui-height` sets a fixed height. Without it, components wrap the measured
root content, respecting an explicit target minimum. The sample banner takes 112dp instead of
its authored 160dp. Documents without measurable root content retain the authored fallback.

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

Web sessions default to IndexedDB for blobs and small named documents. A session with playable cached templates reads
its catalog immediately and refreshes in the background. A cached manifest without playable
blobs first retries prefetch, so an interrupted download cannot register an empty component catalog.
Template rendering does not
wait for a network response. The existing repository still owns expiry, stale grace, install
policy, blob eviction and image-cache metadata. Browser storage can be cleared or evicted by
the browser; this is a cache, not durable application data.

Set `persistentCache: false` in `LiverySession.open` for memory-only sessions (also useful in
hosts that deny IndexedDB or Web Locks). Persistent caching requires a secure origin
(HTTPS or localhost) and Web Locks; refreshes sharing a namespace are serialized across sessions and tabs. Cache namespaces include the catalog and trust configuration:
switching from an unsigned development catalog to signed trust cannot reuse the old manifest.
The browser suite reloads with all catalog requests blocked and checks that cached templates
draw, that memory-only sessions cannot use this cache offline, and that a different trust configuration
cannot inherit the cached unsigned catalog. Serving an application's shell offline remains the
host application's responsibility.

### Wasm distribution size

`webDist` runs Kotlin's pinned Binaryen optimizer before packaging. On the 0.6.0 baseline this
reduces raw Wasm from 4,749,595 to 1,454,082 bytes (69.4%). Ktor remains in the module to preserve
its fetch and redirect contract. `docs/WASM-SIZE.md` records section and dependency measurements;
`scripts/wasm-size.py` reproduces the breakdown for subsequent builds.

Content height is read from the player's normal layout at the current viewport constraints.
The size query does not run a second measure pass or alter expressions, animations or scroll
state. Fill-height, weighted-height and scrolling layouts retain their authored-height fallback.

When replacing a cached catalog, refresh keeps the previous manifest active until its required
replacement templates and assets finish downloading. Failed downloads keep the playable cache;
the accepted serial watermark is still persisted immediately so rollback protection survives a
failure or interruption. Kotlin and Swift use the same activation order.

## Contract queries and named documents

`@livery/a2ui` uses the Kotlin client compiled to Wasm and the TypeScript player. It accepts
contract inputs, design preferences, inline documents, exact hashes and contract queries.
The agent's `template: {"query": {"offer": "summer"}}` sends only params to the app's endpoint:

```typescript
const session = await LiverySession.open({
  catalog: "https://catalog.example/main/",
  keys: [{ keyId: "app", publicKey: publicKeyBase64 }],
  decisionEndpoint: "https://app.example/v1/decisions/query",
  decisionContext: {
    platform: "web", locale: "en-GB", appVersion: 4, bucketingId: appScopedId,
  },
});
```

The installed automatic template draws while the query runs, then the decided template takes
its place if it can play and its data binds. A missing or invalid answer keeps the automatic
choice. The Lit component handles the background fetch; a custom host calls `fetchPending()`
when a render returns `pending`, then renders again. Named hashes never substitute another design.
A custom host reports an impression after drawing each new `presentationKey`, while using
`documentKey` to decide when to reload the player. A server decision can reuse the automatic
document and still receive its own impression.
A standalone signed URL must use a catalog host; its redirects, byte size, hash and signature are
checked. Without an explicit page height, the measured root height respects the target minimum;
the authored height is used when measurement is unavailable.

Compose offers the same forms through `LiveryA2uiHost(decider = ..., decisionContext = ...,
documentFetcher = ...)`. Its height rule also applies to inline and named documents. Surface
identity and `createSurface` theme access still require upstream A2UI API changes.
