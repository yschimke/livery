# @livery/web: standalone component preview (experimental)

A browser RC player and local component-preview host using Livery's Kotlin/Wasm
selection, binding, trust and action gates. No A2UI or UI Builder runtime is required.
The first fixture is the quantity stepper in `samples/two-way`; Confetti integration
is deferred. This is a local implementation prototype, not a hosted sharing service.
The standalone package is currently built from source; it is not published independently.

## Run

From the repository root:

```sh
./gradlew :livery-web-core:webDist :livery-publish:installDist
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
import { ComponentPreview } from '@livery/web';

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
accepts host-provided `examples` as input overrides. Every resulting dataset and `set`
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
