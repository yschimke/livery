# @yschimke/livery-web: standalone component preview (experimental)

A browser RC player and local component-preview host using Livery's Kotlin/Wasm
selection, binding, trust and action gates. No A2UI or UI Builder runtime is required.
The first fixture is the quantity stepper in `samples/two-way`; Confetti integration
is deferred. This includes an experimental viewer distribution for a host to serve; it does not
deploy a hosting service.
The standalone npm package is currently built from source, not published independently.
A successful future release also attaches a `livery-preview-viewer-<version>.zip`.
A draft or failed release does not establish public artifact availability.

## Run

From the repository root:

```sh
./gradlew :livery-web-core:webDist :livery-publish:installDist :livery-capture-cmp:sampleCatalogs
cd web/core
npm ci
npm run build
npm test
npm run demo
```

The demo command prints a loopback URL. It builds the sample catalog and stages
`samples/component-preview/preview.json`; two players deliberately have independent
state. The UI offers examples, light/dark theme, width, reset and the checked action log.
No edit is published and no account state is changed.

Tests use Playwright. Install its Chromium with `npx playwright install chromium`,
or set `CHROMIUM_EXECUTABLE_PATH` to a compatible existing Chromium executable.
Set `LIVERY_RENDER_OUT` to save a screenshot from the actual browser test.

## Embed

```ts
import { ComponentPreview } from '@yschimke/livery-web';

const preview = new ComponentPreview(container, descriptorUrl, {
  config: {
    keys: [{ keyId: 'publisher', publicKey: '<base64 Ed25519 key>' }],
  },
});
await preview.ready;
// Later, when removing the host:
preview.dispose();
```

`unsigned: true` is used only by the local sample. Publisher trust, allowed action
kinds, image hosts and player capabilities are host configuration, never descriptor
permissions. The experimental descriptor is version 1, preview mode only, same-origin
HTTPS references (HTTP only on loopback for development), and an exact design/hash pin. Unknown versions, rejected trust,
unavailable pins and invalid data retain a readable fallback. This is not yet a
portable publication standard or support for following a mutable “latest” channel.

`ComponentPreview` is a DOM host, not a custom element. `WebSession` exposes the typed
Wasm bridge and `RcCanvasPlayer` the neutral canvas player for other hosts. The preview
accepts host-provided `examples` as input overrides. `controls: "minimal"` keeps
theme/reset only; `showActionLog: false` hides developer action diagnostics. Every resulting dataset and `set`
action passes through the Kotlin gates; TypeScript does not duplicate their rules.

Each viewer owns its session/data. Closing it cancels session work, closes its HTTP
client, removes observers/listeners and destroys its player. Offscreen/background
players are destroyed and recreated from retained local values when visible; a pending
one-shot resource fetch may finish while offscreen. Rendering itself is cache-only,
with missing resource downloads performed separately. Data/theme changes keep the
document when its document key is unchanged. Geometry changes reload the same bytes because
the vendored player otherwise retains stale click regions after reflow. Image/long-value changes can require new
bytes; removing a bound value reloads to restore the document's default.

The effective host width participates in selection, not just canvas scaling. A host
narrower than a compatible template gets fallback. The implementation has not established
screen-reader semantics for the drawn RC controls, custom catalog-font support, or broad
operation parity across players. Keep text/link fallback and do not advertise unsupported
capabilities. The default standalone document budget is 256 KiB; hosts can configure it.

## Existing A2UI adapter

`web/a2ui` depends on this package's player, while its optional `livery-web` Wasm
facade delegates shared work to `livery-web-core`. Build the core package before
installing/building the local A2UI package. A2UI bundles its player dependency so
installed tarball consumers do not need a sibling checkout. After changing the
core package locally, rerun `npm ci` in `web/a2ui` to refresh its installed copy. Its existing component API remains intact.
The checked-in vendor player and provenance now live in `web/core/vendor/`.

## Distribute a publication viewer

After `npm run build`, run `npm run bundle`. This produces `viewer-dist/` and
`build/livery-preview-viewer-development.zip`, including the player, Kotlin/Wasm,
styles, provenance, license and an asset inventory. Set `LIVERY_VIEWER_VERSION` to
label a build. The runtime is self-contained; a host does not need npm, an import
map, A2UI or a Livery checkout. Mount the directory at `/assets/livery-preview/`
and generate publication HTML using [HOSTING.md](HOSTING.md)'s exact bootstrap
attributes. Host-owned keys remain separate from publication data.

The full-page viewer offers Bluesky's ordinary compose intent, system sharing where
supported, and clipboard/manual copying. Each shares the canonical revision URL,
without local edits or automatic posting. Embed mode retains theme/reset and a link
to the full page. Server-rendered metadata, static fallback, descriptor discovery,
publication storage and iframe policy belong to the host.

`npm run test:viewer` extracts the ZIP and runs real Chromium/RC interaction under
a revision URL and same-origin CSP, including share activation, denied/unsupported
APIs, embed mode, trust failure and no-JavaScript fallback. It requires the built
publisher CLI; `LIVERY_CLI` can point to an already installed CLI. To keep the local
fixture server open for inspection, run `node test/viewer.mjs --serve`.

For a separately running publication server with the quantity-stepper fixture,
`LIVERY_PUBLICATION_URL=http://127.0.0.1:8080/p/quantity-stepper/r/fixture-1/ npm run test:host`
checks actual SSR/discovery/thumbnail routes, desktop/mobile interaction and embed
mode. It does not start a server or build another repository. `LIVERY_RENDER_OUT`
saves screenshots from both browser test modes.
