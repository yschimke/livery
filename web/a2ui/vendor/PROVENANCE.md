# Vendored: the TypeScript Remote Compose player

`remote-compose-player.mjs` is the TypeScript Remote Compose player, an ES module built with
esbuild (`--bundle --format=esm --platform=browser --target=es2022 --minify --external:canvas`)
from `src/web/main.ts`. It draws a document on a `<canvas>` in the page, and its host API
(`createHostApi`) sets named values on the document while it plays and reports its host actions.

- Source: [yschimke/rc-players](https://github.com/yschimke/rc-players) `third_party/remote-compose-player`,
  `agent/player-measured-size` at commit `6e00009c9d49393c5881d07fc168bfc077a88fa7` ([#835](https://github.com/yschimke/rc-players/pull/835)), exposing the root layout measured size, which includes
  [yschimke/rc-players#829](https://github.com/yschimke/rc-players/pull/829) (NaN-boxed `spacedBy`
  and rounded-clip radii resolved, and a click's definitions loaded with the document, without
  which these templates lay out at `NaN` and their buttons don't fire), itself vendored from
  [yschimke/remotecompose-experiments](https://github.com/yschimke/remotecompose-experiments)
  `players/typescript/` (fork of `camaelon/remotecompose-experiments`).
- License: Apache-2.0.

It isn't published to npm. When it is, this package depends on it instead.
`remote-compose-player.d.mts` declares only the parts `@livery/a2ui` uses.
