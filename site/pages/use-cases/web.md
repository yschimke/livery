title: The web
order: 17

# The web

## A2UI components (built)

`@livery/a2ui` (`web/a2ui`, not yet published to npm) supplies Livery components for A2UI's Lit
renderer. Kotlin's `livery-web` Wasm module owns selection, trust, binding and the action gate.

```ts
const session = await LiverySession.open({ catalog: 'https://example.com/catalog/', keys });
const { catalog } = await liveryCatalog(session, {
  catalogId: 'https://example.com/a2ui/catalog/v1',
  player: 'cmp', // optional; 'ts' is the default
});
```

Both players draw directly in the component's DOM, with no iframe. Data changes push named
values into the playing document. CMP uses rc-players' Compose Multiplatform implementation,
with an optional Wasm/Skiko bundle and fonts prepared before rendering. TS-only pages fetch none
of those assets. Serve `dist/livery-web/` and, for CMP, `dist/livery-web-compose/` beside the JS
bundle. Up to eight CMP viewports are reserved per page; additional components use TS to limit
GPU contexts. A custom `(box, onAction) => LiveryPlayer` factory can supply another renderer.

The same theme, height rules and checked action routing apply to both choices. Neither promises
identical pixels to another player. See the repository's `web/a2ui/README.md` and `docs/A2UI-CMP.md`
for build commands, browser tests, font coverage and measured costs.

## Standalone web component (planned)

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
