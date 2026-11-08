# @livery/a2ui

Livery contracts as [A2UI](https://a2ui.org) components on the web, for A2UI's Lit, React and Angular renderers. An agent places a component per contract and sends its data. The page then picks a
reviewed, signed template for it, binds the data through the contract, and plays the template on a
canvas in the page with the TypeScript Remote Compose player (the default), or Compose Multiplatform
compiled to Wasm (`player: 'cmp'`). A data change sets named values on the
playing document; it never reloads it. Actions in the template become A2UI events, or are written
back to the data model.

The player is vendored from the commit recorded in `vendor/PROVENANCE.md`, with the fixes this package needs
([yschimke/rc-players#829](https://github.com/yschimke/rc-players/pull/829)): without them, these
templates lay out at `NaN` and their buttons don't fire.

The work is done by Livery's own Kotlin client compiled to Wasm (`livery-web`), which this package
ships in `dist/livery-web/`. That covers selection, trust, binding, instantiation and the action
gate, so the web behaves like every other Livery client. See [docs/A2UI.md](../../docs/A2UI.md).

## Use

```ts
import '@a2ui/lit/v0_9';
import { MessageProcessor } from '@a2ui/web_core/v0_9';
import { LiverySession, liveryCatalog } from '@livery/a2ui';

const session = await LiverySession.open({
  catalog: 'https://example.com/catalog/',            // manifest.json is beside it
  keys: [{ keyId: 'main', publicKey: '<base64 raw Ed25519 key>' }],
  appKinds: ['navigate'],                             // kinds this page handles itself
});
const { catalog } = await liveryCatalog(session, {
  catalogId: 'https://example.com/a2ui/catalog/v1',   // what the client advertises
  onAppAction: (kind, action, args) => { /* navigate, … */ },
  onProblem: (component, message) => { /* tell the agent */ },
});
const processor = new MessageProcessor([catalog], (action) => sendToAgent(action));
```

Then render surfaces with `<a2ui-surface>` as usual. `session.catalogSchema(catalogId)` returns
the components' JSON schema to prompt an agent with. `livery build --a2ui` writes the same schema
at publish time.

### Player choice

```ts
const { catalog } = await liveryCatalog(session, {
  catalogId: 'https://example.com/a2ui/catalog/v1',
  player: 'cmp', // omit or use 'ts' for the default
});
```

`'cmp'` prepares the optional `livery-web-compose` module, Skiko and bundled Roboto Flex / Noto
Sans Symbols 2 fonts before returning the catalog. Rendering and value updates never fetch fonts.
It mounts one Compose viewport in the component's DOM, with no iframe. Both players keep the
same document loaded across data changes; changes to baked images or `long` values change its key.
The theme, height rule, session, decision ID and action gate are shared.

Each CMP viewport owns a Skia context. At most eight are reserved across the page; additional
components use TS for their lifetime. Disconnecting releases the viewport and its reservation.
This keeps long chats from exhausting browser WebGL contexts; it is not a performance guarantee.

A custom `player: (box, onAction) => LiveryPlayer` implements `show`, `resize`, `forget`, `dispose`
and `stats`. `show` resolves `false` when superseded or disposed; it resolves `true` after drawing.
For the synchronous `liveryComponent` API, pass `player: await prepareCmpPlayer()` instead of the
`'cmp'` shorthand. Runtime or font preparation failures reject `liveryCatalog`; a document the
prepared CMP player cannot render follows the usual chosen-design/automatic fallback route.

See [the comparison and measured costs](../../docs/A2UI-CMP.md). CMP uses the same Kotlin player
as desktop, but no player is the specification or a promise of Android-identical pixels.

### Serving

The page serves this package's `dist/livery-web/` (fetched beside `dist/livery.js`, `.wasm` as
`application/wasm`) and the catalog (its `manifest.json` and blobs). The player is in the bundle:
`vendor/remote-compose-player.mjs` (vendor/PROVENANCE.md).

The module's glue imports `@js-joda/core`, a dependency of this package. A bundler resolves it; a
page without one uses an import map. For CMP, also serve `dist/livery-web-compose/` beside the
package's JS modules, preserving its `fonts/` subdirectory. A TS-only page never requests it.
`prepareCmpPlayer` and the catalog option compute asset URLs relative to their module; when
bundling, copy both Wasm directories beside the resulting bundle. Serve `.wasm` as `application/wasm`.

### Theme and size

Each theme role (`primary`, `surface`, `onSurface`, …) comes from the first of these that is set:

1. For `primary`, the agent's `createSurface` theme: `primaryColor`.
2. `--livery-<role>`, for example `--livery-on-surface`.
3. A2UI's `--a2ui-color-<role>`.
4. Material 3's baseline colour.

Templates therefore match the basic components around them. The width the element gets selects
the design. The height is `--livery-a2ui-height` when the page sets it; otherwise it's the
measured root content at that width, clamped to the target's `minHeightDp` when declared.
Documents without a finite root measurement use their authored height as the fallback.
The sample banner wraps to 112dp instead of its 160dp authored viewport.

## Develop

```sh
build-brief ./gradlew :livery-web:webDist :livery-web-compose:webDist :livery-publish:installDist   # the Wasm module, and the CLI the test builds a catalog with
npm ci && npm run check && npm run build
npm test                                                    # headless Chromium (Playwright)
```

`npm test` serves the sample catalog to a page that runs A2UI's own engine with each of the Lit, React and Angular renderers, then
checks rendering, interactions and offline behavior for both players:

- both templates draw on a canvas in the page, with no iframe;
- an action reaches the agent with only its declared arguments, and a `set` writes the data model;
- a data change sets values on the playing document without reloading it, and redraws it;
- Livery's events carry the A2UI decision id, `a2ui:<surfaceId>/<componentId>`;
- a surface's `primaryColor` colours its templates over the page's CSS theme;
- a component wraps measured content unless the page sets its height;
- a second catalog's components keep their own session;
- container resizing, disconnect/reconnect, queued shows and disposal during loading;
- fifteen simultaneous components respect the eight-CMP-viewport bound.

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
hosts that deny IndexedDB). Cache namespaces include the catalog and trust configuration:
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

`LIVERY_TEST_PLAYER=ts` or `cmp` selects one player; `LIVERY_RENDERER=lit`, `react` or `angular`
selects a framework when running `node test/e2e.mjs` directly. `LIVERY_RENDER_OUT` saves screenshots
and load measurements. `CHROMIUM_PATH` (or `PLAYWRIGHT_CHROMIUM_EXECUTABLE`) selects an installed
Chromium. CMP retains authored height when it cannot expose a finite root measurement.

Content height is read from the player's normal layout at the current viewport constraints.
The size query does not run a second measure pass or alter expressions, animations or scroll
state. Fill-height, weighted-height and scrolling layouts retain their authored-height fallback.
