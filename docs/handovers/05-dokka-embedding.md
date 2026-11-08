# 05 — KDoc/Dokka interactive component embeds

Read [coordination](README.md). **Goal:** a documented component has source, an interactive
published RC example and a useful fallback in generated HTML.

## Start independently

Build a tiny Kotlin/Dokka sample against a fixed fixture preview URL. Read the current
[Dokka plugin documentation](https://kotlinlang.org/docs/dokka-plugins.html) and
[KDoc documentation](https://kotlinlang.org/docs/kotlin-doc.html).
Choose and record a supported Dokka/Gradle version. Inspect the target repo's build before
adding plugin infrastructure; this is an optional build adapter, never a runtime dependency.

## Deliver

1. A normal KDoc preview link that remains usable without the plugin. Map a known link or
   build-time sample/symbol entry to an embed; do not invent a required KDoc extension.
2. A separate build mapping from documented symbol/sample and release version to published
   revision URL. `@sample` presents Kotlin source; it does not export/run arbitrary Compose.
   Consume 01/03's capture publication rather than compiling application callbacks in a page.
3. Insert 02/03's hosted iframe in generated HTML, retaining source, thumbnail/link fallback,
   accessible title, reasonable size and lazy activation. Check the docs site's CSP and
   preview site's framing policy. A static example remains useful when JS/network is absent.
4. Keep release documentation pinned to its matching artifact. Detect missing mappings or
   accidental “latest” references according to an explicit build policy. Avoid making docs
   generation depend on a live editor session or publish operation.
5. Document what works in generated HTML versus IDE KDoc popups and Markdown outputs. Initially
   those other hosts get a normal link; do not promise iframe/JavaScript execution there.

## Acceptance and boundaries

Generate one real API page containing the Confetti example (or a fixture while 01 is pending),
open it in a browser, interact/reset, navigate by keyboard, and test unavailable preview.
Two embeds must not share local state. Capture an example page and build instructions.

02 owns player controls/state; 03 owns routes/CSP; this stream owns Dokka transformations and
release mappings. Do not duplicate player runtime or introduce Kotlin Playground as a
requirement. Return plugin compatibility constraints and whether iframe sizing needs a small
postMessage contract; coordinate origin validation with 02/03 if it does.
