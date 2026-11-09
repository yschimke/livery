title: Component previews
order: 11

# Interactive component previews

The experimental standalone web host plays the quantity-stepper fixture without A2UI
or the UI Builder editor. Two embeds can share published RC bytes while retaining
independent local values. Its controls demonstrate validated `set` actions, example
inputs, theme changes, width and reset.

Build and run from a Livery checkout:

```sh
./gradlew :livery-web-core:webDist :livery-publish:installDist
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

**Planned:** public revision publishing, UI Builder share controls, Dokka embedding,
Heron rendering, Confetti public-session data and later AT interoperability. None is
required by the local quantity-stepper demonstration.
