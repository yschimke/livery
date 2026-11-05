# AGENTS.md

Livery is a general toolkit for server-driven Remote Compose templates. Read
[`docs/DESIGN.md`](docs/DESIGN.md) before changing the protocol or a module boundary.

## Rules

- **Nothing app-specific in Livery.** No Home Assistant, Lovelace, UI Builder, or any one
  authoring tool or host in `livery-protocol` or `livery-client`. An app's concepts are data
  (contracts, tags, action kinds) or an adapter the app owns. Support for an authoring tool or
  design catalog is a separate, optional module.
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
- **Rendering never waits on the network**, and a chosen design that can't play falls back to the
  automatic one, never to nothing. Automatic selection never picks an `OnRequest` design that
  isn't installed.
- Git history attributes work only to the human committer: never an AI `Co-authored-by:` trailer
  or an agent author/committer identity. Session links and "Generated with" footers are fine.
  Install the hooks once per clone: `scripts/install-git-hooks.sh`.
- Branch names use `agent/...`. Commit subjects and PR titles use Conventional Commits.
- Merge only when explicitly asked.

## Checks

Android targets compile against `compileSdk` 37.1 (Compose Multiplatform and OkHttp 5 require it): set `ANDROID_HOME` and
install `platforms;android-37.1`. The Swift package needs Xcode; CI runs it on macOS.

```sh
./gradlew build                 # compile every target + every test
swift test                      # the Swift package, on macOS
./gradlew :livery-publish:installDist && livery-publish/build/install/livery/bin/livery build samples/catalog build/sample-catalog
scripts/test-agent-attribution.sh
```

CI's required check is the aggregate `gradle` job (which needs `build` and, when a change touches `swift/`, `Package.swift` or `conformance/`, `swift`) (`.github/workflows/ci.yml`): add a job to its
`needs`, never to the ruleset. The merge policy is applied from
[yschimke/renovate-config `repo-policy/`](https://github.com/yschimke/renovate-config/tree/main/repo-policy).
