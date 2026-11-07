# Vendored: the TypeScript Remote Compose player

`remote-compose-player.mjs` is the TypeScript Remote Compose player, an ES module built with
esbuild (`--bundle --format=esm --platform=browser --target=es2022 --minify --external:canvas`)
from `src/web/main.ts`. It draws a document on a `<canvas>` in the page, and its host API
(`createHostApi`) sets named values on the document while it plays and reports its host actions.

- Source: [yschimke/rc-players](https://github.com/yschimke/rc-players) `third_party/remote-compose-player`,
  commit `466d2273d9691f66ba410d7bcd7d9a9f529ed8d8` ([yschimke/rc-players#829](https://github.com/yschimke/rc-players/pull/829):
  NaN-boxed `spacedBy` and rounded-clip radii resolved, and a click's definitions loaded with the
  document, without which these templates lay out at `NaN` and their buttons don't fire; rebuild
  from `main` once it merges), itself vendored from
  [yschimke/remotecompose-experiments](https://github.com/yschimke/remotecompose-experiments)
  `players/typescript/` (fork of `camaelon/remotecompose-experiments`).
- License: Apache-2.0.

It isn't published to npm. When it is, this package depends on it instead.
`remote-compose-player.d.mts` declares only the parts `@livery/a2ui` uses.
