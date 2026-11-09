# Livery publication hosting

`livery-server` is an optional JVM 17 library for serving installed Remote Compose
publications. It supplies filesystem validation and a Ktor adapter; the application
supplies its engine, configuration, public artifact directory and security policy.
It depends on no Compose Preview Server, editor or rendering implementation.

This first scope is read-only hosting of immutable public examples. Catalog upload,
editor export, shared mutable data, decisions and agent sessions remain separate work.
The preview descriptor is experimental, not an AT Protocol standard.

## Install in a Ktor application

Use `ee.schimke.composeai:livery-server:<version>` from a release that includes this
module. A source checkout or staged local publication is not a released dependency.
The application chooses its Ktor engine; this library uses Ktor 3.6.1 and JVM 17.

```kotlin
import ee.schimke.livery.server.ComponentPublications
import ee.schimke.livery.server.PublicationStore
import io.ktor.server.routing.routing
import java.nio.file.Path

routing {
  ComponentPublications(
    store = PublicationStore(Path.of("/srv/publications")),
    viewerRoot = Path.of("/srv/livery-viewer"),
    publicOrigin = "https://preview.example.com",
    viewerConfig = """{"keys":[{"keyId":"publisher","publicKey":"BASE64_ED25519_KEY"}]}""",
  ).install(this)
}
```

Install only the directory containing explicitly public artifacts. The library never
exports an editor design, reads application credentials or grants authoring access.
Its configuration is passed by the host; it reads no environment variables.

Install the adapter in root routing: this first version uses fixed paths and requires
an origin without a path prefix. It preserves these paths:

- `/p/<publication>/r/<revision>/`: fallback page, metadata and viewer mount.
- `/p/<publication>/r/<revision>/embed/`: compact viewer with the canonical page as its share target.
- `/p/<publication>/r/<revision>/preview.json`, `thumbnail.png` and inventoried `catalog/` files.
- `/assets/livery-preview/`: the separately installed viewer's JS, CSS and Wasm.

It supports GET/HEAD and content validators. The viewer directory is optional; the
page remains useful as static HTML without it. Build the portable viewer following
[`web/core/HOSTING.md`](../web/core/HOSTING.md), and install publications following
[`samples/component-preview/PUBLICATION.md`](../samples/component-preview/PUBLICATION.md).

## Storage and policy

`PublicationStore` contains no Ktor or HTTP response logic. It checks the complete
bounded inventory, descriptor identity, artifact hashes and filesystem containment
before returning a publication. Missing or invalid bundles remain unavailable;
a retained `withdrawn` marker takes precedence over the bundle and cache validators.
The HTTP adapter maps these outcomes to 404, 410 or the requested representation.

The inventory is an operator-local installation marker, not a public protocol or trust
signature. Livery's existing runtime still owns catalog trust, template selection,
contract binding and action admission. The browser's signing keys and capabilities
come from the host configuration, never from publication data. Unsigned admission is
an explicit development option; production examples use trusted public signing keys.

Hosts own authentication boundaries and CSP. Permit WebAssembly on the publication
pages, same-origin viewer assets, and framing on the exact embed route. Ordinary pages
can retain `frame-ancestors 'self'`. Integrate these allowances with the host's existing
policy; installing the adapter does not replace application plugins or global headers.
See the viewer's hosting instructions for script, style and Permissions Policy details.

The initial filesystem store revalidates the entire bounded inventory for each request.
Measure it before high-volume use. Keep roots operator-owned, retain withdrawn revision
identities, and deploy viewer directories as complete units. Previously downloaded public
bytes cannot be recalled.

## Verification

```sh
./gradlew :livery-server:test
node --test scripts/tests/install-component-preview.test.mjs
```

The library runs in a standalone Ktor test host. Shared publication cases also exercise
the Node installer. Host-specific authentication, settings and framing integration remain
tests in the consuming application.
