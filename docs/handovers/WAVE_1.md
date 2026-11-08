# First wave: local component previews and incoming shares

Implementation date: 2026-11-08. These are local development changes, not deployed
services or a published AT Protocol extension. Confetti work remains on hold until
its relevant upstream PRs merge, including further investigation of the draft stack.

Delivery: [Livery PR #46](https://github.com/yschimke/livery/pull/46). The branch is pushed,
with current main (`10cef3a`) integrated in `dde2095`. The PR's required `gradle` check
tracks the complete build, catalog workflows and rebuilt standalone/A2UI browser matrix.
The local results below describe the initial implementation and integration checks;
consult the PR for validation of its latest commit.

## Delivered scope

- **00 — Shared fixture:** an experimental version-1 preview descriptor pins Livery's
  existing quantity-stepper design and hash. Executable fixture cases exercise the
  existing binder, selector and action router. This does not change the normative
  catalog protocol or add a shared application backend.
- **02 — Standalone web:** `livery-web-core` and `@livery/web` expose a neutral runtime,
  canvas player and embeddable `ComponentPreview` DOM host. A local demo shows two
  independently interactive instances, example inputs, theme, width, reset and action
  logs. The existing optional A2UI facade uses the shared core and player.
- **06 — Heron incoming sharing:** Android plain-text shares open an editable ordinary
  composer after restoration/sign-in. Cold/warm intent handling, retained pending
  shares, cancellation, duplicate delivery and initial link detection are covered in
  code and tests. Posting still requires the user's normal composer action.

The quantity stepper is a development fixture. It does not replace the intended
Confetti component or public conference-session demonstration.

## Try the local preview

Follow [the standalone package README](../../web/core/README.md). From Livery's root:

```sh
./gradlew :livery-web-core:webDist :livery-publish:installDist
cd web/core
npm ci
npm run build
npm run demo
```

Open the loopback URL printed by the demo. Changing one instance's quantity leaves
the other unchanged. The sample uses an explicitly unsigned local catalog; publisher
trust and capabilities are host configuration, never permissions granted by the
descriptor. HTTPS is required except for loopback development.

The neutral host can be embedded by constructing `ComponentPreview(container,
descriptorUrl, options)` and calling `dispose()` when it is removed. See the package
README for the exact API and signed-publisher configuration.

## Verification

Livery integration branch: `agent/rc-component-plan` in `/workspace/livery`.
Fixture commit: `690559f`. Standalone implementation: `42cea92` (cherry-picked from
`423b13b` in the isolated web worktree).

Passed on the original implementation tree, before integrating `origin/main` at
`10cef3a` (these are baseline results, not validation of the merged tree):

- `:livery-client:jvmTest :livery-web-core:jvmTest :livery-web:jvmTest`: 116 tests
  (98 client, 3 core and 15 A2UI), including shared-fixture and action-admission cases.
- Both Wasm production bundles and both npm packages' TypeScript checks/builds.
- Standalone Playwright suite: 21 assertions with actual canvas taps, independent
  state, theme changes, resized tap regions, fallback, visibility and disposal.
- Existing A2UI Playwright suite: 14 assertions, including event routing, two-way
  binding, separate catalogs and theme/height behavior.
- Publisher CLI builds `samples/catalog`: 6 designs, 11 templates, 4 contracts.
- Attribution checks: 44/44. Documentation site: all 23 pages build, including on
  the integrated branch. `git diff --check` passes.

The repository-wide `./gradlew build` was attempted with a single worker and bounded
compiler memory. Writable Kotlin tooling and test-JVM cache paths were needed in this
sandbox. After those adjustments, all 33 existing A2UI Compose JVM tests also passed.
The full build then stopped while creating Android dependency transforms because the
workspace ran out of disk space (426 tasks considered). **That local repository-wide
build did not complete.** PR #46 runs the full check on GitHub Actions; the focused
successful checks above do not substitute for that required gate. Swift/device tests
were not run locally.

Heron is a separate checkout at `/workspace/rc-wave1/heron`, branch
`agent/heron-incoming-share`, based on upstream `097a68215eb26f5eeed71c4e744897890f5afbf1`.
Implementation commit: `5a337c0` (`feat(android): open incoming text shares in the post composer`).
The branch is preserved in [yschimke/heron](https://github.com/yschimke/heron/tree/agent/heron-incoming-share)
at [the tested commit](https://github.com/yschimke/heron/commit/5a337c083640f11f68937415fad098b5e6ef27a5).
Keep this work in the fork while upstream integration is deferred; recheck upstream
changes and rerun validation before proposing it there. No upstream PR has been opened.

Its scaffold desktop tests pass: 36 total, including 11 new incoming-share cases.
Both new composer tests pass, covering initial Unicode URL formatting/link-preview
seeding and the ordinary empty composer. Desktop production compilation and repository
`spotlessApply spotlessCheck` also pass.

Heron's original all-ABI `:androidApp:assembleDebug` attempt exhausted disk during
APK packaging after all Android source compilation succeeded. After deleting incomplete
outputs and unused task caches, `:androidApp:assembleDebug
-Pandroid.injected.build.abi=arm64-v8a` passed (1,068 tasks considered). The 119 MiB arm64
debug APK passed ZIP integrity checking; its native libraries are arm64-only. The merged
manifest retains VIEW filters and contains the `singleTask`/`SEND text/plain` receiver.
This is **arm64 packaging validation**, not a green all-ABI build or a device test.
The local APK is at
`/workspace/rc-wave1/heron/apps/androidApp/build/intermediates/apk/debug/androidApp-arm64-v8a-debug.apk`.

Local browser evidence is retained at
`/workspace/rc-wave1/evidence/standalone-preview.png` and
`/workspace/rc-wave1/evidence/a2ui-web.png`. Regenerate it using `npm test` with
`LIVERY_RENDER_OUT` set to the desired screenshot path.

## Integration with current main

The first-wave branch now incorporates main's named/signed template forms, contract
queries, persistent browser cache, CMP player and Lit/React/Angular adapters. Shared
preparation/action routing remains in the neutral Kotlin core. The A2UI tarball
bundles the shared player; it does not require a sibling source checkout.

Local integration checks cover TypeScript, Angular compilation, tarball structure,
attribution and documentation. Tarball inspection uses the previously built Wasm
files solely to validate packaging. Newly rebuilt Wasm, JVM and full browser-matrix
results must come from the PR CI; local disk constraints prevent a fresh Gradle build.
No upstream or standalone tests were disabled. CI retains Lit/React/Angular on both
players, React 18 compatibility, and adds the standalone fixture suite.

## Limits and next assignments

The browser implementation proves local interaction and independent state. It does
not establish shared live data, mutable publication channels, catalog-font parity,
or screen-reader semantics for drawn RC controls. Readable fallback remains required.
Geometry changes reload the same RC bytes because the vendored player otherwise
retains stale click regions after reflow; data/theme updates keep the document.

There is no connected Android device or emulator in this environment. Heron's
browser-share-sheet, OAuth-return, notification and process-recreation smoke matrix
is documented in its `docs/android-text-sharing.md`; device validation remains open.

Next, 03 can implement revisioned public hosting and 07 can implement native preview
rendering against the same fixture. 04 editor sharing and 05 Dokka can use mocks until
hosting is available. Keep 01 and 08 parked until Confetti merges. AT schema design
and standardisation follow evidence from working public integrations.

Hosting readiness found that the server's current pinned builder release disables RC
authoring/export at compile time. Existing authenticated revision-selected export APIs
can be reused once an enabled RC build is available, but a publication route alone cannot
enable that capability. Prebuilt fixture publication can proceed independently. Keep
artifact publication separate from source-design visibility: public-design readers can
expose document/history, which a component share should not implicitly publish.

The complete ui.coo.ee → public preview URL → OS share sheet → Heron composer → post
journey is still a later integration milestone. This wave supplies the local viewer
and receiving composer only; it does not claim a successful end-to-end public post.
