title: The web
order: 17

# The web

> **Planned.**

**In one line:** a `<livery-template>` element that fetches, verifies and plays a template with
rc-players' TypeScript player, fed by the same catalogs.

## The plan

- A TypeScript client (`fetch`, `crypto.subtle` Ed25519, Cache API storage) ported from the Kotlin
  one and checked against the shared conformance fixtures.
- A web component over `remote-compose-player-js`.

Today, the Kotlin client already compiles for Wasm (Ktor's `fetch` engine), and `livery-compose`
runs in the browser through Compose Multiplatform.

**Hosting for the web:** GitHub release downloads send no CORS headers, so a browser must read a
catalog from GitHub Pages, a CDN or a design server instead ([Hosting](../hosting.md)).

### npm releases

`@livery/a2ui` follows the repository version. Release-please updates its package and lockfile;
merging the release PR builds and checks the npm tarball, then publishes it alongside Maven.
The repository owner must configure `NPM_TOKEN` with publish access to `@livery/a2ui` before the
first npm release. No npm release has been made yet. `npm run pack:check` checks the files and
relative runtime imports in the actual tarball in CI. The TypeScript player remains vendored
with its provenance until rc-players publishes a supported npm package.
