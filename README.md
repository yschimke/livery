# Livery

Server-driven [Remote Compose](https://developer.android.com/jetpack/androidx/releases/compose-remote)
templates for mobile, widget, watch and web apps. Publish a design once; every app screen, launcher
widget, Wear widget and web page that plays it takes it on, without an app release.

Livery is the plumbing between "a `.rc` document exists" and "it is on screen with the right data":

- **Fetch, verify and cache** templates from any host: a static site, a **GitHub release**, a
  CDN, a design server, or app assets. Signed manifests (Ed25519, per-publisher keys that can be
  limited to some contracts), content-addressed blobs, rollback protection, offline-first fallback.
- **Pick** the right document for each player: surface, Remote Compose API level, op profile,
  size, theme, Wear container.
- **Fill it with data** through a typed **contract**, by live push (in-app) or by writing values
  into the document (launcher and Wear widgets), with one validation gate everywhere.
- **List, preview and install** designs and themes, including a live preview of one not yet
  downloaded.
- **Decide** what a placement shows (local rules with schedules, segments, rollouts and frequency
  caps, or your own decision server), render **feeds** of data-only items, route **actions**
  through an allowlist, and report **events**.
- **Author with anything** that produces `.rc` bytes, and work with **any AI workflow**: CI on
  pull requests, live co-editing sessions, generated campaign data, runtime personalisation.

The same model serves interactive ads, growth features, feeds and live dynamic content such as
Home Assistant cards. Read [docs/DESIGN.md](docs/DESIGN.md).

## Status

Early. In this repository today:

| Module | |
| --- | --- |
| `livery-protocol` | Kotlin Multiplatform wire types: manifest, contracts, designs, templates, targets, themes, placements, decisions, feeds, events |
| `livery-client` | Kotlin Multiplatform client: sources, trust, cache, selection, install, preview, decisions, feeds, actions, events; JVM fetcher, crypto and file stores |
| `livery-publish` | `livery` CLI: validate a source tree, write a static or GitHub-release catalog, sign, verify |
| `samples/catalog` | a promo banner, a feed article and a live tile, with launcher and Wear widget targets (placeholder `.rc` bytes) |

Planned: Compose Multiplatform and Android players, launcher and Wear widget hosts, a native Swift
package, a web client, an optional server and agent (MCP) tools. See the
[roadmap](docs/DESIGN.md#roadmap).

## Try it

```sh
./gradlew build :livery-publish:installDist
alias livery=livery-publish/build/install/livery/bin/livery

livery keygen sample                                   # sample.key (secret), sample.pub (embed in apps)
livery build samples/catalog build/catalog --key sample.key --key-id sample-1
livery verify build/catalog --pub sample.pub --key-id sample-1

# As GitHub release assets:
livery build samples/catalog build/release --github <owner>/<repo> --tag catalog-1 --key sample.key --key-id sample-1
gh release create catalog-1 build/release/*
```

In an app:

```kotlin
val repo = TemplateRepository(
  source = GitHubReleaseSource("acme", "app-templates"),
  fetcher = JvmFetcher(), crypto = JvmCrypto,
  trust = TrustPolicy.Signed(listOf(TrustedKey("acme-1", publicKeyBytes))),
  blobs = FileBlobStore(cacheDir.resolve("livery/blobs")),
  kv = FileKeyValueStore(filesDir.resolve("livery")),
  clock = { System.currentTimeMillis() },
  targets = listOf(inAppPlayer, launcherWidgetPlayer),
)
repo.refresh()                                         // from your background scheduler
val plan = SlotPlanner(repo, analytics, clock).forDecision(
  TemplateQuery("promo/banner@1", inAppPlayer, widthDp = 360),
  decider.decide("home.top", context) ?: return,
)
```

## License

Apache 2.0.
