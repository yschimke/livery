# Publishing under the personal npm scope

The public package is `@yschimke/livery-a2ui`. Its standalone player dependency,
`@yschimke/livery-web`, is bundled into the tarball and does not need a separate npm
publication. These npm names do not change Kotlin modules or Maven coordinates.

## First publication

The npm account must own or have publish access to the `@yschimke` scope. Sign in with
`npm login` and check `npm whoami`. Use Node 24 and npm 11.5.1 or later.

The CI web job uploads a `livery-npm` artifact after its browser tests pass. Choose a fully
passing CI run containing the scope change, verify its commit and package version, and
replace `RUN_ID` below with that run's numeric ID. Download into a fresh directory:

```sh
gh run download RUN_ID --repo yschimke/livery --name livery-npm --dir ./livery-npm
npm login
npm publish ./livery-npm/yschimke-livery-a2ui-*.tgz --access public
npm view @yschimke/livery-a2ui version
```

This publishes the CI-built tarball without a local Kotlin/Wasm build. CI itself never
publishes this artifact to npm.

### Alternatively, build locally

A source build also needs JDK 17 and an Android SDK with platform 37.1 (`ANDROID_HOME`).

Use a checkout containing the scope change, not the older `v0.8.0` tag, which still names
`@livery/a2ui`. From the repository root:

```sh
npm --prefix web/a2ui ci
./gradlew :livery-web-core:webDist :livery-web:webDist :livery-web-compose:webDist \
  --max-workers=2 -Pkotlin.daemon.jvmargs=-Xmx4g \
  -Plivery.wasmOpt="$PWD/web/a2ui/node_modules/binaryen/bin/wasm-opt"
npm --prefix web/core ci
npm --prefix web/core run check
npm --prefix web/core run build
cd web/a2ui
npm ci
npm run check
npm run build
npm run pack:check
npm publish --access public
npm view @yschimke/livery-a2ui version
```

Complete npm's interactive authentication/2FA prompt. Publishing reserves the package
version permanently: choose the intended version before publishing and use a new version
for changed bytes. The first manual publication is a deliberate operator action, not part
of tests or PR validation.

## Configure npm trusted publishing

Open the package's Settings on npmjs.com, then Trusted Publisher → GitHub Actions:

| Field | Value |
| --- | --- |
| Organization or user | `yschimke` |
| Repository | `livery` |
| Workflow filename | `release.yml` |
| Environment | Leave blank |
| Allowed actions | Allow direct `npm publish` |

The workflow filename has no directory prefix. This configuration belongs to the npm
package; no GitHub `NPM_TOKEN` secret is required. See
[npm's trusted-publishing documentation](https://docs.npmjs.com/trusted-publishers/).

The `publish-npm` job runs on GitHub-hosted Ubuntu with `id-token: write`, a supported npm
CLI, and `npm publish --access public`. npm obtains short-lived authentication through
GitHub OIDC. No token environment variable or generated authentication `.npmrc` is used.

## Subsequent releases

Release-please updates the A2UI package version. A release from a commit containing these
changes builds and validates the tarball, then publishes through the configured trust.
Use a version newer than the manually bootstrapped version. Re-running an older failed
workflow does not pick up this change; recovering an old tag also checks out its old
package name. Prefer the next normal release containing the rename.

The standalone viewer ZIP is still a GitHub release asset. Maven publishing is independent
of npm authentication; the overall GitHub release remains draft until all release jobs pass.
