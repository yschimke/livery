# Experimental component-preview fixture

This is a local integration fixture, **not a public protocol or deployed publication**.
It uses the existing [quantity stepper](../two-way/README.md). Confetti integration is on hold.
The JSON envelope is shared by the standalone web sample and future native-host experiments;
it does not add a production module or change Livery's normative protocol.

## Files and artifact identity

- `preview.json`: version 1 preview descriptor and immutable example inputs.
- `cases.json`: accepted/rejected data and an action trace for two independent preview sessions.
- `../two-way/contracts/quantity.json`: the real contract, including its bounded `set` action.
- `../two-way/designs/quantity-stepper/in-app.rc`: the actual captured document. Its SHA-256 is
  pinned in the descriptor; changing it requires an intentional fixture revision.

The catalog and document are not copied into this directory. Build the existing source tree
when staging a runnable sample, then copy the descriptor beside the generated catalog:

```sh
./gradlew :livery-publish:installDist
livery-publish/build/install/livery/bin/livery build samples/two-way build/component-preview/catalog
cp samples/component-preview/preview.json build/component-preview/preview.json
```

These commands prepare data; the web stream supplies the viewer and serving command. The
sample build is unsigned and must be explicitly admitted only by a local development host.
A public deployment needs configured publisher trust and signed catalogs; a descriptor must
never enable unsigned trust itself. Catalog generation times/expiry are not pinned to this
checked-in fixture: content identity is the exact RC hash, and normal expiry rules still apply.

## Host interpretation (experimental version 1)

1. Accept only `schemaVersion: 1` and `mode: "preview"`. Unknown versions or modes produce
   ordinary title/description/link fallback. Unknown extra fields carry no capabilities.
2. Resolve `canonicalUrl` and `catalog.manifestUrl` against the fetched descriptor URL using
   standard URL resolution. In this first sample, require the same origin and reject userinfo
   and non-HTTP(S) schemes. HTTP is for the explicitly configured local development host;
   public hosting uses HTTPS. Redirects must not escape the host's allowed-origin policy.
3. Admission is host configuration: trusted origin, publisher keys, allowed contracts and
   player capabilities. The descriptor cannot supply trust roots or disable validation.
   Its origin must be admitted to refer to that catalog; a catalog signature alone does not
   authenticate arbitrary descriptor data or URLs.
4. `template` uses Livery's existing `TemplateRef` fields. In this fixture all three are
   required, including the exact SHA-256. Use the existing repository/selection gates and
   `ContractBinder`; never silently play different bytes under this pinned publication.
   `TemplateRepository.ensure` already supports exact eligible references. If a host uses
   a different planning entry point, verify the prepared design/hash before passing it to
   the player. Unsupported capability, missing bytes or a pin mismatch produces fallback.
5. Prepare/download outside rendering. Cached `RenderPlan` and values drive the player;
   neither the descriptor nor its document obtains arbitrary network access.
6. Copy `data` into each preview's own state. `publicationId` and `revision` identify reusable
   content, not a shared mutable session. A validated `set` writes to that preview's input;
   invalid values and undeclared actions do not mutate state. Reset restores descriptor data.
   Host theme values are supplied separately, not persisted as shared preview state.
7. This sample supports only host-handled `set`; there are no resource endpoints, custom
   scripts, account actions, analytics destinations or application callbacks in the descriptor.
8. `fallback` supplies plain text; treat it as text, never HTML. Use the resolved canonical
   URL for ordinary navigation. No thumbnail is supplied by this first fixture. Public
   publication metadata/images, ready/error responses and discovery are hosting-stream work.

The envelope is intentionally internal. Hosting must agree discovery routes and resource/size
budgets with consumers before exposing it publicly. A stable URL is not a promise to bypass
expiry/revocation or retain every old catalog forever.

## Validation and remaining checks

`ComponentPreviewFixtureTest` in `livery-client` checks the pin against the actual file, binds
the descriptor and data cases with the existing contract, checks target eligibility, and
replays the action trace through `ActionRouter` into independent host states. It asserts that
out-of-range, malformed and undeclared actions do not change either preview.

```sh
./gradlew :livery-client:jvmTest --tests '*ComponentPreviewFixtureTest'
```

This is fixture validation, not proof of browser/native playback or production publication.
Consumer integration tests must additionally cover descriptor admission/version/URL failures,
signatures, missing network resources, local reset, disposal and fallback presentation. Existing
core conformance remains unchanged; this directory does not claim cross-port descriptor support.
