# Preparing an immutable publication

This is an experimental, operator-run local workflow. It prepares a public copy of one
quantity-stepper fixture for the publication server. It does not publish to a remote service
or establish a new AT Protocol standard.

Use a built Livery CLI with the `play` command (`:livery-publish:installDist`), the built sample
catalogs (`:livery-capture-cmp:sampleCatalogs`), Java and Node 22 or later. The preparation script
builds the actual two-way catalog (`livery-capture-cmp/build/samples/two-way`) with inspection,
binds the descriptor's example data through Livery, and renders the captured RC document to
a 720×160 PNG. The image is a real static rendering; interactive playback belongs to the viewer.

```sh
node samples/component-preview/prepare-publication.mjs \
  "$PWD/livery-publish/build/install/livery/bin/livery" \
  build/component-preview-bundle fixture-1
node scripts/install-component-preview.mjs \
  build/component-preview-bundle build/publications
node --test scripts/tests/install-component-preview.test.mjs
```

The second command reports `installed` and creates
`build/publications/quantity-stepper/r/fixture-1/`. Repeating it with the **same prepared
directory** reports `unchanged`, without rewriting the installed revision. Building a new
catalog changes its generation/expiry timestamps: use a new revision when publishing new
bytes. Preparation refuses an existing output directory. Retain the complete prepared bundle
for retries rather than regenerating it.

The fixture catalog is unsigned. Only an explicitly configured local development host may
admit it. Public hosting requires the operator's publisher trust configuration and a signed
catalog produced by the normal Livery CLI. Signing keys stay outside the bundle; the installer
copies an optional `catalog/manifest.sig.json` unchanged and does **not** validate trust or
sign anything. Recipients still run Livery's signature, expiry, capability, binding and action
gates. Installation is not proof that a particular player can render a publication.

For a signed bundle, prepare a new revision, then sign its catalog **before** installation.
The key and public-key files below are existing operator-managed files outside the bundle:

```sh
node samples/component-preview/prepare-publication.mjs \
  "$PWD/livery-publish/build/install/livery/bin/livery" \
  build/signed-component-preview signed-1
livery-publish/build/install/livery/bin/livery build \
  livery-capture-cmp/build/samples/two-way build/signed-component-preview/catalog --inspect \
  --key /private/publisher.key --key-id publisher
livery-publish/build/install/livery/bin/livery verify \
  build/signed-component-preview/catalog --pub /operator/publisher.pub --key-id publisher
node scripts/install-component-preview.mjs build/signed-component-preview build/publications
```

Configure the server's `SERVE_COMPONENT_PUBLICATIONS_DIR` with that publication root,
`SERVE_COMPONENT_PUBLICATIONS_ORIGIN` with its HTTPS public origin and
`SERVE_COMPONENT_VIEWER_DIR` with the separately built viewer directory. Set
`SERVE_COMPONENT_VIEWER_CONFIG` to the following JSON, replacing the public key with the raw
base64 Ed25519 public key from `publisher.pub` (never the private key):

```json
{"keys":[{"keyId":"publisher","publicKey":"BASE64_PUBLIC_ED25519_KEY"}]}
```

The same object is stored as a **JSON string** in `settings.json` under
`componentPublications.viewerConfig`. See `docs/development/COMPONENT_PUBLICATIONS.md` in
the `compose-preview-server` repository for the full server setup. The descriptor cannot
provide keys or enable unsigned mode. Development-only `{"unsigned":true}` is a separate,
explicit operator setting, not part of the signed configuration.

Thumbnail rendering uses the renderer's system fonts. Check the image before installation;
if the host lacks glyph coverage, configure its font environment (for example
`FONTCONFIG_FILE` on Linux) and prepare a new bundle. Never alter an installed thumbnail.

## Complete bundle and local inventory

A bundle must contain `preview.json`, `thumbnail.png`, `catalog/manifest.json`, and every
local catalog template, asset and preview referenced by that manifest, including previews owned
by designs, individual templates and themes. Each referenced preview must be present and match
its SHA-256. The descriptor keeps
`canonicalUrl: "./"` and `catalog.manifestUrl: "./catalog/manifest.json"`. Catalog bases must be
relative subdirectories inside `catalog/`; URLs with origins, root paths, traversal, query
strings or encoded path components are rejected. Image proxies and Google font downloads
are outside this offline format. Unreferenced files, scripts, executable files, private key
files and source-provided inventories are rejected. The operator must still review all public
example data and metadata: arbitrary secrets inside legitimate JSON cannot be detected by
a filename or checksum validator.

The installer writes `publication-files.json`, a **plain JSON object** mapping each public
relative filename to its lowercase SHA-256. It excludes itself. This operator-local allowlist
is not a public resource or a trust signature; the server must never serve it. No HTML, viewer
code, source history, editor credentials or publishing keys belong in the bundle. Viewer assets
are a separate operator-installed build.

Limits shared with the server: 128 files, 16 MiB per file, 32 MiB total, 1 MiB per catalog JSON
file, 64 KiB for the entire `preview.json` descriptor and 64 KiB for the inventory. JSON limits
count UTF-8 bytes, including whitespace, rather than characters. Fallback text is limited to
200 characters for the title and 2000 for its description.
IDs/revisions match `[a-z0-9][a-z0-9-]{0,63}`. Relative paths are at most
240 ASCII characters; each segment matches `[A-Za-z0-9][A-Za-z0-9._-]{0,99}`. Supported file
types are RC, PNG/JPEG/WebP, TTF/OTF/WOFF2, catalog binary assets and the catalog JSON files.
SVG and arbitrary active documents are deliberately unsupported by this first distribution.

## Visibility, retries and withdrawal

Validation finishes before any destination is created. Files and inventory are then written
to a hidden staging directory beside the final revision and renamed together. The server only
serves complete, allowlisted revisions; partially staged files never receive publication URLs.
This requires a local filesystem supporting atomic directory rename and operator-owned source
and destination directories. Grant the server account read/traverse access to the publication
root and copied files; the operator's umask may otherwise restrict them. Concurrent identical
installers yield one install and successful
no-op retries. Changed bytes under the same identity, damaged installed files and symlinks are
rejected. This protects against malformed bundles; it is not a sandbox against another process
with permission to mutate directory ancestors during installation.

To withdraw a revision, the operator creates the regular marker file `withdrawn` inside its
revision directory. The server returns `410` with `no-store` for that publication's routes.
Keep the marker to reserve the identity even if its public artifacts are later removed. The
installer refuses a withdrawn identity and never removes the marker or overwrites a revision.
Clients that already cached public bytes may retain them; withdrawal is not remote deletion.

No remote deployment, public posting or Android share-sheet validation is performed by these
commands. Use the server's configured public origin to obtain the canonical URL after install.
