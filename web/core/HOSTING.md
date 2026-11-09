# Hosting the standalone viewer

This directory is a prebuilt browser distribution, with no A2UI runtime, npm server,
CDN or source checkout required by the host. Mount its contents at
`/assets/livery-preview/` (another prefix works too). Serve `.js` and `.mjs` as
`text/javascript`, `.wasm` as `application/wasm`, and `.css` as `text/css`.
Keep the `livery-web/` directory beside `viewer.js`. `bundle.json` inventories the
build; its hashes are integrity diagnostics, not a publisher signature.

The host renders the publication HTML, metadata and a readable static fallback.
For a publication at `/p/quantity-stepper/r/fixture-1/`, use:

```html
<link rel="canonical" href="/p/quantity-stepper/r/fixture-1/">
<link rel="alternate" type="application/vnd.livery.preview+json"
      href="/p/quantity-stepper/r/fixture-1/preview.json">
<link rel="stylesheet" href="/assets/livery-preview/viewer.css">
<!-- Optional layout/fallback hooks; the fallback remains outside the mount. -->
<main class="livery-publication-page">
<section data-livery-fallback><!-- Server-rendered title, text, thumbnail and link. --></section>
<div data-livery-preview data-mode="page"
     data-descriptor="/p/quantity-stepper/r/fixture-1/preview.json"
     data-canonical="/p/quantity-stepper/r/fixture-1/"
     data-config='{"keys":[{"keyId":"publisher","publicKey":"BASE64_ED25519_KEY"}]}'></div>
</main>
<script type="module" src="/assets/livery-preview/viewer.js"></script>
```

Escape every HTML attribute at the server. `data-config` is the operator's trusted
host configuration; never copy it from a publication or request parameter. It takes
`ComponentPreview`'s `config` shape, excluding `catalog`. Omitting keys fails closed.
The local unsigned fixture must explicitly configure `{"unsigned":true}`; that is
for development/preview channels only. Descriptor/catalog/canonical URLs must be on
the host origin, using HTTPS except loopback development. The descriptor and catalog
keep the existing version-1 preview and exact template/hash semantics.

At `/p/quantity-stepper/r/fixture-1/embed/`, set `data-mode="embed"` and retain the
absolute descriptor and canonical page URLs above. Embed mode shows theme/reset and
an open-page link; the full page includes width and sharing controls. The root mount
is replaced by the enhancement. With the optional wrapper/hooks above, CSS hides the
static image after a successful render, and the whole fallback section in embed mode.
A failed render or no JavaScript retains it. Outside fallback must remain useful when JavaScript,
Wasm, catalog trust or component rendering fails. No iframe messaging is required.

The host owns CSP and frame policy. The module/Wasm need same-origin script and
connect access and WebAssembly compilation (`'wasm-unsafe-eval'` in `script-src`).
The player updates element styles, so permit inline style attributes (or the host's
corresponding `style-src-attr` policy). Only the embed page needs to permit framing.
Allow clipboard-write/web-share in Permissions Policy only when the host wants those
features; denied or unsupported APIs retain copy/manual-link alternatives.

Bluesky opens its normal compose intent with the canonical revision URL as text.
System sharing and clipboard calls run directly inside a fresh click. Opening a
composer does not post. Local component changes are not included in the shared URL,
are not published and do not alter another person's preview. No login is required by
this viewer; it is intended for public, non-sensitive publication content.

The host should deploy the complete versioned asset directory together. Roll back
by pointing the asset mount at a prior complete directory. Livery does not fetch or
build viewer sources across repositories at server startup. Public release assets
are available only after the corresponding release successfully publishes; a local
build is not a released dependency.
