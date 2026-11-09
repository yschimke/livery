# Reusable publication server library

The public-preview implementation now belongs in Livery's optional `livery-server`
module. Compose Preview Server consumes it as a library and remains responsible for
application configuration, authentication boundaries, CSP, deployment and editor integration.

The library has two layers:

- `PublicationStore`: synchronous filesystem loading, descriptor/inventory validation,
  bounded reads, digest checks, containment and withdrawal state. No HTTP dependencies
  appear in its store implementation.
- `ComponentPublications`: Ktor GET/HEAD routes, metadata and fallback HTML, descriptor
  discovery, public artifacts, viewer assets, cache validators and withdrawal responses.
  It installs no global application plugins or authentication policy.

The existing public URLs, viewer bundle and installation format are preserved. Catalog
trust, selection, binding and action admission remain the existing Livery runtime's gates.
The optional library does not pull in an editor, player, CLI, catalog builder or Compose
Preview Server. Other Kotlin servers can use the same hosting implementation.

Shared publication cases check the installer and JVM store's ID and metadata bounds;
the descriptor byte cases also drive the browser distribution tests. Standalone Ktor
tests exercise the library without Compose Preview Server or its AutoHeadResponse plugin.
The consuming server retains tests of settings, authentication isolation, CSP and actual
HTTP delegation. See [the module instructions](../../livery-server/README.md).

## Release sequence

The Livery module is included in normal build tests and the Maven publication list.
It must be merged and published through Livery's normal release workflow before the
host's default dependency graph can resolve it. The intended first version is currently
0.8.0; if that release lands before this module, use the first subsequent release that
actually contains `ee.schimke.composeai:livery-server`.

For pre-release integration, Compose Preview Server's existing staged-publication path
accepts `--version-property livery.version`. This stages a unique snapshot into an explicit
local repository and selects it only with `-PlocalDependencies=...`. No `mavenLocal`,
new composite build, vendored source or normal-build fallback is added. Keep the consumer
PR in draft until its released coordinate exists and the default checks pass.

Confetti remains deferred. Heron remains in the yschimke fork. This extraction adds no
AT schema, deployment, editor publishing or shared mutable-data behavior.

## Extraction validation

- 15 standalone JVM store/Ktor tests; 53 installer cases; 24 browser distribution assertions.
- 36 consuming-server HTTP, settings, page-policy and private-design isolation tests against
  the staged Maven artifact, plus dependency-boundary and JVM 17 floor checks.
- 22 assertions against the running server and signed catalog: desktop/mobile rendering,
  local RC interaction, canonical Bluesky sharing, compact embeds and no-JavaScript fallback.
- Three staged-publication helper tests, 44 attribution-hook checks and a 23-page site build.

The browser production distribution is unchanged by this extraction. Full repository checks
still run in CI; the consumer's normal graph requires the published module described above.
