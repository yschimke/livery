# @livery/a2ui

Livery contracts as [A2UI](https://a2ui.org) components on the web, for A2UI's Lit renderer
(`@a2ui/lit`). An agent places a component per contract and sends its data. The page then picks a
reviewed, signed template for it, binds the data through the contract, and plays the template on a
canvas in the page with the TypeScript Remote Compose player. A data change sets named values on the
playing document; it never reloads it. Actions in the template become A2UI events, or are written
back to the data model.

The player is vendored from rc-players `main`, which has two fixes this package needs
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

### Serving

The page serves this package's `dist/livery-web/` (fetched beside `dist/livery.js`, `.wasm` as
`application/wasm`) and the catalog (its `manifest.json` and blobs). The player is in the bundle:
`vendor/remote-compose-player.mjs` (vendor/PROVENANCE.md).

The module's glue imports `@js-joda/core`, a dependency of this package. A bundler resolves it; a
page without one uses an import map.

### Theme and size

Each theme role (`primary`, `surface`, `onSurface`, …) comes from the first of these that is set:

1. `--livery-<role>`, for example `--livery-on-surface`.
2. A2UI's `--a2ui-color-<role>`.
3. Material 3's baseline colour.

Templates therefore match the basic components around them. The width the element gets selects
the design. The height is `--livery-a2ui-height`, 200px by default.

## Develop

```sh
./gradlew :livery-web:webDist :livery-publish:installDist   # the Wasm module, and the CLI the test builds a catalog with
npm ci && npm run check && npm run build
npm test                                                    # headless Chromium (Playwright)
```

`npm test` serves the sample catalog to a page that runs A2UI's own engine and Lit renderer, then
checks five things:

- both templates draw on a canvas in the page, with no iframe;
- an action reaches the agent with only its declared arguments, and a `set` writes the data model;
- a data change sets values on the playing document without reloading it, and redraws it;
- Livery's events carry the A2UI decision id, `a2ui:<surfaceId>/<componentId>`;
- a second catalog's components keep their own session.
