# KDoc with a live Remote Compose preview

This optional sample generates a real Dokka API page, then adds two independently
interactive embeds of Livery's existing quantity-stepper publication. It documents
the component's example-input model, not a second implementation of its UI.

KDoc contains ordinary HTTPS links and an `@sample` reference. IDE popups, Markdown
and unmodified Dokka output retain those links and Kotlin source. `embed.py` is an
explicit HTML build adapter, not a Dokka plugin or a runtime dependency of Livery.
It does not compile/run Compose, publish a component, contact an editor, or fetch a
publication while generating documentation. The iframe loads the actual published
RC viewer only when a reader opens the generated page.

## Generate

The isolated sample pins Kotlin 2.3.21 and Dokka 2.3.0 and uses the repository's
Gradle 9.8.2 wrapper and JDK 17. The versions follow Dokka's
[2.3.0 Gradle example](https://github.com/Kotlin/dokka/blob/v2.3.0/examples/gradle/dokka-gradle-example/build.gradle.kts).
They are independent of Livery's published modules. See also the official
[Dokka configuration](https://kotlinlang.org/docs/dokka-gradle-configuration-options.html)
and [KDoc `@sample` documentation](https://kotlinlang.org/docs/kotlin-doc.html#sample-identifier).

From the repository root:

```sh
./gradlew -p samples/dokka-preview dokkaGeneratePublicationHtml --max-workers=1
python3 samples/dokka-preview/embed.py \
  --input samples/dokka-preview/build/dokka/html \
  --output samples/dokka-preview/build/dokka/embedded \
  --mapping samples/dokka-preview/previews.json \
  --documentation-version fixture-1
python3 -m http.server 8080 --directory samples/dokka-preview/build/dokka/embedded
```

Open the generated `quantityStepperInputs` API page from the documentation index.
The checked-in `preview.coo.ee` links are fixture examples, not a claim that this
revision is already deployed. Install that publication and viewer on the chosen
host before expecting the embeds to play. For local verification, append
`--development-origin http://127.0.0.1:PORT` to the adapter command, using the local
publication server's port. That explicit loopback-only override changes generated
embed/fallback destinations; original KDoc links stay intact.

Gradle resolves build dependencies on first use. With those dependencies cached,
the same Dokka task also runs with `--offline`; Dokka's external documentation
lookups are disabled, and the adapter itself has no network operations.

## Release mapping and fallback

`previews.json` maps exact KDoc link URLs to an expected publication/revision, title,
height and occurrence count. `documentationVersion` must match the build argument.
Generation fails on a missing/extra mapped link, mutable revision such as `latest`,
non-HTTPS production URL, credentials, query strings, mismatched revision or invalid
mapping. It never silently embeds another revision or rewrites unmatched links.

The adapter copies Dokka's HTML and assets, preserving source and existing links.
Each embed has an accessible title, lazy loading, a static thumbnail and a visible
link outside the iframe. No adapter JavaScript is needed. Kotlin Playground is
excluded: `@sample` is source documentation, not a remotely compiled demo.

The documentation host's CSP must permit the publication origin in `frame-src` and
`img-src`, and same-origin styles in `style-src` for `livery-preview.css`. The
publication host must allow framing its explicit `/embed/` route. The iframe uses
`allow-scripts allow-same-origin allow-popups`; serve only a viewer host you trust.
This is not a promise of accessible semantics for RC canvas controls. The text and
link fallback remain available, including when script execution is disabled or the
viewer is unavailable. Fixed, mapped iframe height avoids any cross-origin resize
message protocol.

Only the separate generated output directory is replaced on repeated adapter runs.
The adapter refuses an unrelated existing destination, nested input/output paths,
symlinked input or previously transformed HTML as input.

## Checks

```sh
python3 -m unittest discover -s samples/dokka-preview -p 'test_*.py'
```

This checks mapping/fallback preservation and rejection behavior. Real Dokka HTML
generation and browser interaction with the hosted viewer are separate integration
checks; successful adapter unit tests alone do not prove those integrations.

The browser check uses actual generated Dokka HTML and an independently installed
publication, with Playwright already installed by `npm ci` in `web/core`:

```sh
LIVERY_PUBLICATION_ORIGIN=http://127.0.0.1:8080 \
  node samples/dokka-preview/test-browser.mjs
```

It starts its own temporary documentation server, verifies the original source/link
output, plays two real iframes, taps the first RC canvas, checks independent state
and reset, then exercises unavailable-viewer and JavaScript-disabled fallback.
Set `CHROMIUM_EXECUTABLE_PATH` when using a system Chromium, and `LIVERY_RENDER_OUT`
to save a screenshot. Publication installation is a separate operator step; see
[the fixture instructions](../component-preview/PUBLICATION.md).
