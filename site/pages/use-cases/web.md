title: The web
order: 17

# The web

> **A2UI integration available; standalone element planned.**

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

## A2UI components

`@livery/a2ui` uses the Kotlin client compiled to Wasm and the TypeScript player. It accepts
contract inputs, design preferences, inline documents, exact hashes and contract queries.
The agent's `template: {"query": {"offer": "summer"}}` sends only params to the app's endpoint:

```typescript
const session = await LiverySession.open({
  catalog: "https://catalog.example/main/",
  keys: [{ keyId: "app", publicKey: publicKeyBase64 }],
  decisionEndpoint: "https://app.example/v1/decisions/query",
  decisionContext: {
    platform: "web", locale: "en-GB", appVersion: 4, bucketingId: appScopedId,
  },
});
```

The installed automatic template draws while the query runs, then the decided template takes
its place if it can play and its data binds. A missing or invalid answer keeps the automatic
choice. The Lit component handles the background fetch; a custom host calls `fetchPending()`
when a render returns `pending`, then renders again. Named hashes never substitute another design.
A standalone signed URL must use a catalog host; its redirects, byte size, hash and signature are
checked. Without an explicit page height, the template's minimum or authored height is used.

Compose offers the same forms through `LiveryA2uiHost(decider = ..., decisionContext = ...,
documentFetcher = ...)`. Its height rule also applies to inline and named documents. Surface
identity and `createSurface` theme access still require upstream A2UI API changes.
