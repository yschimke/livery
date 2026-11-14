# AGENTS.md

Livery is a general toolkit for server-driven Remote Compose templates. Read
[`docs/DESIGN.md`](docs/DESIGN.md) before changing the protocol or a module boundary.

## Rules

- **Nothing app-specific in Livery.** No Home Assistant, Lovelace, UI Builder, or any one
  authoring tool or host in `livery-protocol` or `livery-client`. An app's concepts are data
  (contracts, tags, action kinds) or an adapter the app owns. Support for an authoring tool or
  design catalog is a separate, optional module.
- **Source in PRs is Kotlin; `.rc` is build output.** A template authored in Compose is committed
  as its Kotlin only; its `.rc`, sidecar and renders are produced by Gradle into `build/` (the
  samples: `livery-capture-cmp`'s `samples/SampleDesigns.kt`, captured on the plain JVM by `:livery-capture-cmp:sampleCatalogs` into
  `livery-capture-cmp/build/samples/`, which tests read through `gradle/sample-catalogs.gradle.kts`)
  or published to an output branch, never committed. Committed exceptions: `conformance/`
  fixtures (shared with the Swift and TypeScript ports), byte-stable test fixtures from other tools
  (`demo-tile.rc`, `livery-verify`'s fixtures), the sample designs with no Kotlin source yet
  (`tile-default`, `tile-retro`, `article-card`, `banner-bold`), and site/PR images.
- **One gate.** Data validity is `ContractBinder`; template selection is `TemplateSelector`. Don't
  fork either per platform or per product; extend them and their tests.
- **Protocol changes are compatible within a schema.** New manifest fields have defaults and
  clients ignore unknown keys; anything an old client would misread bumps `minClient`. Update the
  Swift and TypeScript ports' conformance fixtures with any behaviour change.
- **Ports agree through `conformance/`.** A change to selection, binding, resources or trust
  updates the Kotlin behaviour, regenerates the fixtures
  (`./gradlew :livery-client:jvmTest -Plivery.conformance.update=true`), and updates the Swift
  package in the same PR; CI runs both against the same files. `docs/PROTOCOL.md` is normative:
  change it with the code.
- **The site is documentation users follow.** `site/pages/` must match the code: a change to a
  public API, CLI flag or catalog file updates the pages that show it, in the same PR, and
  anything not built yet stays marked *Planned*. Build it with `python3 site/build.py build/site`
  (needs `pip install markdown`); `.github/workflows/pages.yml` publishes it from `main` to the
  `site` branch, which <https://livery.coo.ee/> serves (yschimke/preview-coo-ee), and points
  GitHub Pages at it with redirects (`site/redirects.py`).
- **No visible redraws.** A card after the first of its template, and any card the app preloaded,
  draws complete on its first frame (`LiveryResources`, `FirstFrameTest`): composition reads
  documents, fonts and images from memory without suspending, and only a template's first card that
  was not preloaded may wait. Never reuse one player state across cards.
- **A slower first frame beats a blank one.** Before a screen's first frame, a slot or template that
  is not ready waits for its work on the main thread, within `LiveryHost.firstFrameBudgetMillis` (150
  ms by default, shared, the work itself off the main thread); a `startup` holding the splash screen
  covers that frame instead. After it nothing waits: what is not ready shows
  `LiveryHost.placeholder`, held to the template's last height (`FirstFrameTest`).
- **Rendering never waits on the network** past that budget, and a chosen design that can't play falls back to the
  automatic one, never to nothing. Automatic selection never picks an `OnRequest` design that
  isn't installed.
- Git history attributes work only to the human committer: never an AI `Co-authored-by:` trailer
  or an agent author/committer identity. Session links and "Generated with" footers are fine.
  Install the hooks once per clone: `scripts/install-git-hooks.sh`.
- Branch names use `agent/...`. Commit subjects and PR titles use Conventional Commits.
- Merge only when explicitly asked.
- **Releases** are cut by release-please (`.github/workflows/release.yml`, as in
  compose-ui-builder): it keeps a `chore(main): release <version>` PR open from the Conventional
  Commit subjects, and merging it tags `v<version>` and publishes, of the modules listed in the root
  `build.gradle.kts` (`published`), the ones that changed (`.github/scripts/maven-publish-plan.sh`,
  `release-tasks.gradle.kts`) to Maven Central as `ee.schimke.composeai:<module>`, with
  `livery-bom` pinning every module at the version it is published at, and the CLIs to the GitHub
  release. Don't push `v*` tags by hand. A new library module goes into that list. A change to the
  plan's rules updates `.github/scripts/test-maven-publish-plan.sh`.

## Review guidelines

- **Do not report commit author, committer or `Co-authored-by:` findings.** Your review sandbox
  applies the pull request as a new commit under your own identity (`Codex <codex@openai.com>`), so
  any identity you read from a local `git log` is yours, not the author's. Such findings name a
  commit that exists only in the sandbox (yschimke/livery#81 was one). Attribution is enforced by the
  `No Agent Attribution` CI gate and the hooks from `scripts/install-git-hooks.sh`; leave it out of
  reviews entirely.

## Checks

Android targets compile against `compileSdk` 37.1 (Compose Multiplatform and OkHttp 5 require it): set `ANDROID_HOME` and
install `platforms;android-37.1`. The Swift package needs Xcode; CI runs it on macOS.

```sh
./gradlew build                 # compile every target + every test
swift test                      # the Swift package, on macOS
./gradlew :livery-publish:installDist :livery-capture-cmp:sampleCatalogs && livery-publish/build/install/livery/bin/livery build livery-capture-cmp/build/samples/catalog build/sample-catalog
./gradlew :livery-render:jvmTest -Plivery.render.out=build/renders   # keep the test renders to look at
scripts/test-agent-attribution.sh
```

CI's required check is the aggregate `gradle` job (which needs `build` and, when a change touches `swift/`, `Package.swift` or `conformance/`, `swift`) (`.github/workflows/ci.yml`): add a job to its
`needs`, never to the ruleset. The merge policy is applied from
[yschimke/renovate-config `repo-policy/`](https://github.com/yschimke/renovate-config/tree/main/repo-policy).
