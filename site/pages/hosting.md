title: Hosting
order: 20

# Hosting

A catalog channel is plain files, so any host works. The app needs one URL: the manifest's. Blob,
asset and preview URLs are resolved from the manifest.

| Host | Source in the app | Build with |
| --- | --- | --- |
| GitHub release | `GitHubReleaseSource(owner, repo)` (`latest`) or with `tag` | `livery build … --github owner/repo --tag T` |
| Static host, GitHub Pages, S3, CDN | `StaticSource("https://host/catalog/main")` | `livery build …` (static layout) |
| Design server channel | `StaticSource("https://server/v1/catalogs/acme/channels/main")` | its upload API |
| App assets (fallback) | `TemplateRepository.Bundled(manifest, read)` | `livery build …`, copied into assets |

## GitHub releases

```bash
livery build catalog build/release --github acme/app-templates --tag catalog-12 --key acme.key --key-id acme-1
gh release create catalog-12 build/release/* --repo acme/app-templates
```

- Assets are flat (`<sha>.rc`, `<sha>.png`); the manifest points `blobBase` at the release's
  download URL.
- `GitHubReleaseSource(owner, repo)` follows `releases/latest`, so keep catalog releases in their
  own repository.
- No CORS headers: fine for apps, not for browsers.

**From CI**, on every merge to the catalog repository's `main`:

```yaml
name: Publish catalog
on: { push: { branches: [main] } }
permissions: { contents: write }
jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
      - uses: actions/checkout@v5
        with: { repository: yschimke/livery, path: livery }
      - uses: actions/setup-java@v5
        with: { distribution: temurin, java-version: 17 }
      - run: cd livery && ./gradlew :livery-publish:installDist
      - name: Build, sign and release
        env:
          LIVERY_KEY: ${{ secrets.LIVERY_SIGNING_KEY }}
          GH_TOKEN: ${{ github.token }}
        run: |
          printf '%s' "$LIVERY_KEY" > acme.key
          tag="catalog-${{ github.run_number }}"
          livery/livery-publish/build/install/livery/bin/livery build catalog build/release \
            --github ${{ github.repository }} --tag "$tag" --key acme.key --key-id acme-1 --inspect
          gh release create "$tag" build/release/* --title "$tag"
```

`--inspect` decodes every `.rc` and refuses one that reads or emits anything its sidecar doesn't
declare, or reads a value as a different type than its contract declares. Use it whenever
documents come from more than one tool, or from an agent.

## Checking a change

`livery check` compares a catalog source tree with an earlier version of itself: a git revision of
its repository, or another tree. Run it on every pull request:

```sh
livery check catalog --against origin/main --inspect
```

It exits 1 when a contract changes in a way apps already installed would break (see
[Concepts](concepts.md#versions-within-a-major)), or when the tree doesn't build. It also lists,
for review, every template added, removed or changed: what it now reads and emits, the contract
minor it requires (apps on an older minor stop getting it), its size, and which values only the
template's own default covers when data leaves them out. `--json` prints the same report for
other tools; agents get it from the MCP server's `check_compatibility`.

The serial defaults to the build time in milliseconds, which only grows. If you pass `--serial`
yourself, never let it go down for a channel: apps refuse a lower one as a rollback.

## Static hosts and CDNs

```bash
livery build catalog public/catalog/main --key acme.key --key-id acme-1
```

Upload `public/catalog/main/` as is. Serve `manifest.json` and `manifest.sig.json` with
revalidation (`Cache-Control: no-cache` and an `ETag`); everything else is immutable
(`max-age=31536000, immutable`). Browsers need `Access-Control-Allow-Origin` on all of it.

## Channels

A channel is a whole catalog under its own URL: `…/main`, `…/staging`, `…/pr-123`. Point
development builds or a hidden settings switch at a non-`main` channel to try a change before it
ships. Apps cache each source separately, so switching back is clean. Set `"channel"` in
`catalog.json`, or keep one source tree and publish it to several URLs.

## Bundled fallback

Ship an initial catalog inside the app, so a first launch with no network still draws. On
Android, the Gradle plugin builds the catalog's source tree into the APK's assets on every build.
It applies the same checks as `livery build --inspect`, and it is reproducible:

```kotlin
plugins { id("ee.schimke.composeai.livery.catalog") version "<version>" }

livery {
  source.set(layout.projectDirectory.dir("livery"))   // catalog.json, contracts/, designs/
  assetsPath.set("livery")                            // the default
}
```

```kotlin
val repository = TemplateRepository(
  source, KtorFetcher(), JavaCrypto, trust, blobs, kv, clock, targets,
  bundled = AndroidBundled.fromAssets(context),       // null if the app ships none
)
```

The bundled catalog is trusted as the APK is: it isn't signature-checked and never expires.
Anything the repository downloads replaces it. A remote source is optional: an app can start with
only the baked catalog and add hosting later. Elsewhere, write a `livery build` output into the
app's resources and pass a `TemplateRepository.Bundled(manifest, read)`.
