# @livery/a2ui

Livery contracts as [A2UI](https://a2ui.org) components on the web, for A2UI's Lit renderer
(`@a2ui/lit`). An agent places a component per contract and sends its data. The page then picks a
reviewed, signed template for it, binds the data through the contract, and plays the template in
rc-players' Remote Compose web player. Actions in the template become A2UI events, or are written
back to the data model.

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
  playerUrl: '/rc-player/index.html',                 // @yschimke/remote-compose-player-cmp's dist/
  onAppAction: (kind, action, args) => { /* navigate, … */ },
  onProblem: (component, message) => { /* tell the agent */ },
});
const processor = new MessageProcessor([catalog], (action) => sendToAgent(action));
```

Then render surfaces with `<a2ui-surface>` as usual. `session.catalogSchema(catalogId)` returns
the components' JSON schema to prompt an agent with. `livery build --a2ui` writes the same schema
at publish time.

### Serving

The page serves three things:

- **The player:** `@yschimke/remote-compose-player-cmp`'s `dist/`, with `.wasm` sent as
  `application/wasm`.
- **This package's `dist/livery-web/`:** it is fetched beside `dist/livery.js`.
- **The catalog:** its `manifest.json` and blobs.

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
checks four things:

- both templates play;
- the banner draws its surface and text;
- an action reaches the agent with only its declared arguments;
- a `set` writes the data model, and a data-model update redraws the component.
