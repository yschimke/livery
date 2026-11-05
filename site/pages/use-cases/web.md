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
