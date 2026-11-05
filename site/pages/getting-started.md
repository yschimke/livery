title: Getting started
order: 1

# Getting started

From nothing to a signed catalog on a GitHub release, read by an app, in five steps.

## 1. Get the tools

The libraries are published to Maven Central as `ee.schimke.composeai:<module>`, from the first
release on. Each release attaches the `livery` and `livery-mcp` CLIs as zips:

```kotlin
dependencies {
  implementation("ee.schimke.composeai:livery-client:<version>")   // fetch, verify, cache, select, bind
  implementation("ee.schimke.composeai:livery-compose:<version>")  // LiveryTemplate, LiverySlot
  // launcher and Wear widgets, notifications: livery-launcher-widgets, livery-wear-widgets, livery-notifications
}
```

The Swift package isn't tagged yet; use it from a checkout. To build the CLI from source:

```bash
git clone https://github.com/yschimke/livery
cd livery
./gradlew :livery-publish:installDist
export PATH="$PWD/livery-publish/build/install/livery/bin:$PATH"
livery
```

Building the Android targets needs `ANDROID_HOME` with `platforms;android-37.1` installed.

## 2. Make a signing key

```bash
livery keygen acme
```

This writes `acme.key` (the private key: a CI secret, never committed) and `acme.pub` (the public
key, which your apps embed). See [Signing](signing.md).

## 3. Write a catalog

A catalog is a directory. The repository's [`samples/catalog`](https://github.com/yschimke/livery/tree/main/samples/catalog)
is a complete one; the smallest is:

```
catalog.json                        { "catalog": "acme", "channel": "main" }
contracts/banner.json               the data the template reads (see Concepts)
designs/banner-classic/design.json  { "id": "banner-classic", "contract": "promo/banner@1",
                                      "title": "Classic", "install": "Always" }
designs/banner-classic/in-app.rc    the document, from any authoring tool
designs/banner-classic/in-app.json  { "target": { "surface": "in-app", "rcApiLevel": 7,
                                      "profiles": ["androidx"] },
                                      "reads": ["headline", "cta"], "emits": ["cta"] }
```

`livery build` refuses a catalog with problems instead of publishing it: a template reading a
value its contract doesn't declare, an example that doesn't fit its contract, a duplicate id. Add
`--inspect` to also decode every `.rc` and check it against its sidecar.

## 4. Publish it as a GitHub release

```bash
livery build catalog build/release --github acme/app-templates --tag catalog-1 \
  --key acme.key --key-id acme-1
gh release create catalog-1 build/release/* --repo acme/app-templates --title "Catalog 1"
```

Use a repository for catalog releases only, so its "latest" release is always the catalog. For
other hosts, see [Hosting](hosting.md).

## 5. Read it in the app

**Kotlin (Android, desktop, iOS)**: depend on a checkout through a composite build:

```kotlin
// settings.gradle.kts
includeBuild("../livery")
// build.gradle.kts
implementation("ee.schimke.composeai:livery-client:0.1.0-SNAPSHOT")
implementation("ee.schimke.composeai:livery-compose:0.1.0-SNAPSHOT")
```

```kotlin
val inApp = PlayerCapabilities(Surfaces.IN_APP, maxRcApiLevel = 7, profiles = setOf("androidx"))
val repository = TemplateRepository(
  source = GitHubReleaseSource("acme", "app-templates"),
  fetcher = KtorFetcher(),           // OkHttp on Android and the JVM, Darwin on iOS
  crypto = JavaCrypto,               // JVM and Android
  trust = TrustPolicy.Signed(listOf(TrustedKey("acme-1", Base64.decode(ACME_PUB)))),
  blobs = FileBlobStore(File(context.cacheDir, "livery/blobs")),
  kv = FileKeyValueStore(File(context.filesDir, "livery")),
  clock = { System.currentTimeMillis() },
  targets = listOf(inApp),
)
repository.refresh()                 // from your background scheduler, and at start
```

**Swift (iOS, macOS)**:

```swift
// Package.swift
.package(url: "https://github.com/yschimke/livery", branch: "main")
```

```swift
let repository = TemplateRepository(
  source: GitHubReleaseSource(owner: "acme", repo: "app-templates"),
  trust: .signed([TrustedKey(keyId: "acme-1", publicKey: Data(base64Encoded: acmePub)!)]),
  blobs: FileBlobStore(directory: caches.appendingPathComponent("livery")),
  kv: FileKeyValueStore(directory: support.appendingPathComponent("livery")),
  targets: [PlayerCapabilities(surface: Surfaces.inApp, maxRcApiLevel: 7, profiles: ["core"])])
_ = await repository.refresh()
```

Then pick a use case: each page goes from the contract to a deployment checklist.

> Rendering never waits on the network. `refresh()` downloads in the background; slots draw from
> what is on disk, then the bundled catalog, then your native fallback.
