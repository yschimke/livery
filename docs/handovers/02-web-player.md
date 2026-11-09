# 02 — Standalone web player and interactive preview

Read [coordination](README.md). **Goal:** render the real Confetti RC artifact in a lightweight
web preview without an A2UI runtime dependency. Own player extraction and viewer assets.

First-wave status: the standalone path is implemented with the quantity-stepper fixture;
see [results and limits](WAVE_1.md) and the [package API](../../web/core/README.md).
Confetti integration remains deferred. Continue from `livery-web-core` and `web/core`;
the paths below describe the extraction's original starting point.

## Starting points in Livery

- `livery-web/src/commonMain/kotlin/ee/schimke/livery/web/LiveryA2uiWeb.kt`
- `livery-web/src/wasmJsMain/kotlin/ee/schimke/livery/web/Exports.kt`
- `web/a2ui/src/player.ts`, `livery.ts`, `catalog.ts`
- `livery-web/build.gradle.kts`, `web/a2ui/package.json`

The original web module depended on `livery-a2ui`; hiding UI elements is not sufficient
decoupling. Its KMP pipeline already supplies core validation/selection. Extract reusable
logic while preserving the A2UI wrapper and public behaviour; avoid a parallel TS engine.

## Deliver

1. A standalone load/update/action/dispose path using the existing shared gates. Choose the
   smallest module/package change justified by actual imports; document dependency impact.
2. A fixture-driven viewer using 00/01. Preview controls: example, light/dark theme, viewport
   width, reset and an action log. Keep controls outside the RC document.
3. Local, per-viewer demo action state. Handle validated writes, reset and unsupported actions;
   no real Confetti bookmark. Verify value changes preserve document identity when possible.
4. A small embeddable viewer mode for 03/05 with sizing and lifecycle behaviour. Pause hidden
   work, dispose listeners/player and preserve a thumbnail/link when loading fails.
5. A versioned distributable asset interface for 03. Do not ship the full UI Builder editor.

## Checks and handoff

Start with the quantity stepper when diagnosing RC features, then the real Confetti card/fonts.
Use JVM pipeline tests plus an actual browser interaction test. Existing commands include
`./gradlew :livery-web:jvmTest :livery-web:webDist` and `npm run check`/`npm run build`
under `web/a2ui`; inspect its test prerequisites and preserve the existing A2UI regression test.

Demonstrate two embeds with independent bookmark state, theme/data updates, disposal, unknown
capability and bad-publication fallback. Record supported operations and any visual difference.
03 owns HTTP publication/routes; 05 owns Dokka integration; coordinate the embed URL contract.
