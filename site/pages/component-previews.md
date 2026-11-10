title: Component previews
order: 11

# Interactive component previews

The experimental standalone web host plays the quantity-stepper fixture without A2UI
or the UI Builder editor. Two embeds can share published RC bytes while retaining
independent local values. Its controls demonstrate validated `set` actions, example
inputs, theme changes, width and reset.

Build and run from a Livery checkout:

```sh
./gradlew :livery-web-core:webDist :livery-publish:installDist :livery-capture:sampleCatalogs
cd web/core
npm ci
npm run build
npm run demo
```

The command prints a local URL; it does not deploy a service. The example deliberately
uses an unsigned local catalog. Real hosts configure trusted publisher keys separately
from the preview descriptor. See `web/core/README.md` for the API, browser tests and limits.

`livery-web-core` contains the host-neutral Kotlin pipeline and standalone Wasm exports;
`@livery/web` supplies its browser bridge and player. `livery-web` and `@livery/a2ui`
remain the optional A2UI adapter over that shared code. Selection, data binding and
host-action validation still use the common Livery gates.

The current descriptor pins a design/hash, permits same-origin references and supports
local preview state. Incompatible or missing content shows fallback. A preview bookmark
or toggle is not an account write. Custom font parity and accessibility of drawn controls
remain to be established before claiming a general-purpose embed.

## Host the viewer

After building the package, `npm run bundle` writes a standalone `viewer-dist/`
and ZIP. A host serves those assets under `/assets/livery-preview/` and renders the
publication page with the descriptor URL, canonical revision URL and operator trust
configuration. Follow `web/core/HOSTING.md`; the server does not need a Livery source
checkout or an A2UI runtime. The optional JVM `livery-server` library supplies a
filesystem publication store and Ktor routes for metadata, fallback pages, embeds and
artifacts. The application supplies configuration, public storage and its security
policy; see `livery-server/README.md`. Consume a release that includes the module;
staged local artifacts are development verification only.

The full page offers Bluesky's normal compose intent, system sharing when available,
and copy-link with a manual fallback. These share the canonical revision, not local
control edits, and never post automatically. Embed mode retains theme/reset and an
open-page link. `npm run test:viewer` exercises the extracted ZIP with the actual RC
player. Successfully published future releases attach the versioned viewer ZIP;
a local build or draft release is not a public dependency.

The operator installer and optional `samples/dokka-preview` example provide immutable
publication and real documentation embeds. The server library makes the read-only
hosting implementation reusable by other Ktor applications.

**Planned:** authenticated editor publishing, UI Builder share controls, Heron rendering,
Confetti public-session data and later AT interoperability. None is required by the local
quantity-stepper demonstration.
