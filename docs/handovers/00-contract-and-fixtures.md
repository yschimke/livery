# 00 — Shared descriptor, host semantics and fixtures

Read [coordination and baseline](README.md) and the [plan](../RC_TILES_PLAN.md).
**Goal:** give all hosts one small experimental publication contract without prematurely
standardising a new protocol. Owner: Livery docs/sample fixtures, not app-specific core logic.

First-wave status: [the quantity-stepper descriptor and executable cases](../../samples/component-preview/README.md)
are implemented. See [the wave report](WAVE_1.md) for checks and remaining publication
semantics. Continue from this fixture rather than inventing a separate descriptor per host.

## Start here

Read `docs/PROTOCOL.md`, especially contracts/actions, signing, resource verification,
selection and `set`; inspect `conformance/`, `samples/two-way/` and the existing web/Compose
entry points. 01 will provide a Confetti artifact. Use the quantity stepper for early tests.

## Deliver

1. A versioned example descriptor and interpretation notes. Identify publication/revision,
   canonical public URL, design/catalog/contract references, example inputs, fallback and
   optional public resource reference. Specify relative URL resolution, size limits and
   unsupported-version behaviour. Agree descriptor discovery with 03/07.
2. A precise trust binding: which authenticated publisher controls the descriptor and which
   signed catalog/design it may reference. Signing RC bytes alone does not authenticate an
   arbitrary descriptor's action destinations. Initially configure origins/keys in hosts.
3. Explicit preview vs real-resource mode. Preview state is local/resettable. Unknown actions
   are rejected/reported; Confetti actions use a host adapter. Personal state is excluded.
4. Fixtures for accepted publication, missing artifact, unsupported capability/version,
   rejected trust, invalid input/action, two independent preview sessions and stable pinning.
   Label simulated failures and avoid changing core conformance semantics needlessly.
5. A small publish/resolve interface note agreed with 03, including not-ready/error responses,
   revision identity and thumbnail/descriptor availability before a URL is shareable.

## Boundaries

Do not define arbitrary remote endpoints, download scripts or add Confetti/Dokka/Heron
concepts to `livery-client` or `livery-protocol`. Start as sample fixtures/internal adapters.
Existing catalog checks remain the one source of binding/selection/trust behaviour.
A pinned revision still obeys expiry/revocation; decide retention separately with 03.

## Acceptance and handoff

02 and 07 can interpret the same fixture without undocumented assumptions; 03 can produce
it; 04/05 can use the canonical URL without knowing its internal storage. Record fixture
version and unresolved decisions. Request changes centrally rather than letting hosts invent
new fields. Validate fixtures using real Livery gates where possible.
