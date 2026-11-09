# Second slice: public component pages and documentation embeds

The first wave merged in [Livery #46](https://github.com/yschimke/livery/pull/46).
This slice turns its standalone player into a distributable viewer and supplies real
publication hosting. It does not enable UI Builder's compile-time-disabled RC exporter,
publish private editor designs, or introduce an AT Protocol schema. Confetti remains
deferred; Heron's incoming-share implementation remains in the yschimke fork.

## Product flow

An operator prepares a component with public example data and a real rendered thumbnail,
then installs that complete bundle as an immutable publication. Readers open its public
page, interact locally, embed it in documentation, or share its canonical URL through
Bluesky's editable composer, the system share sheet or Copy link. Sharing does not submit
a post. Each reader and each embed has independent local state.

The publication has these anonymous routes:

| Resource | Path |
| --- | --- |
| Canonical page and server-rendered social metadata | `/p/<id>/r/<revision>/` |
| Embeddable page | `/p/<id>/r/<revision>/embed/` |
| Experimental preview descriptor | `/p/<id>/r/<revision>/preview.json` |
| Static fallback and social thumbnail | `/p/<id>/r/<revision>/thumbnail.png` |
| Pinned catalog and content-addressed artifacts | `/p/<id>/r/<revision>/catalog/…` |
| Operator-installed viewer assets | `/assets/livery-preview/…` |

The HTML page advertises its descriptor through an alternate link. The embed keeps the
canonical page as its share target. Only the embed route permits external framing; the
normal page retains the server's frame policy. All public content is separate from editor
state, authentication tokens and source history.

## Publication installation and lifetime

The installer accepts a complete prebuilt directory, checks paths, sizes and referenced
artifact hashes, writes `publication-files.json`, then atomically renames staging into
`<publication-root>/<id>/r/<revision>`. That inventory is an operator-local readiness marker,
not a served API or a new portable protocol. Reinstalling identical bytes is a no-op;
different bytes require a new revision. Trust keys and action policy come from host
configuration, never from the descriptor or publication inventory.

A `withdrawn` marker reserves the revision identity and makes its public resources return
410 with `no-store`, including requests with previously valid cache validators. Revalidation
is required for ordinary responses so withdrawal can take effect. Already downloaded data
cannot be recalled from a reader's device.

The server defaults to publication hosting disabled. An operator supplies the publication
directory, HTTPS public origin, viewer directory and viewer policy in its normal settings.
Local loopback HTTP and explicit unsigned admission are development options. Public
deployments use configured publisher keys and signed catalogs.

## Documentation integration

The optional Dokka example keeps normal KDoc links and sample source. A build-time mapping
can add titled, lazy iframes to generated HTML while retaining ordinary links. Generation
does not contact the editor or publish a design. Release mappings pin a publication revision;
IDE and Markdown consumers receive a normal link rather than a promise of live rendering.

## Delivery and verification

The Livery implementation includes the atomic installer, a self-contained viewer ZIP,
sharing controls, lifecycle handling and the optional real Dokka example. The companion
`compose-preview-server` implementation serves the installed artifacts with metadata,
discovery, strict embed policy and withdrawal. Neither requires an npm publication or
cross-repository source build at server startup.

Local verification used freshly built neutral Wasm, actual RC thumbnails, a normally
launched Ktor server and generated Dokka HTML:

- 30 installer tests, including concurrent retries, immutable identity and bounded inputs.
- 21 standalone player assertions and 22 extracted-viewer ZIP browser assertions.
- 43 server tests covering HTTP, settings, page policy and private/public design isolation;
  Gradle formatting and full Caddy configuration validation also passed.
- 22 real-host assertions at desktop and mobile widths for each of the unsigned development
  fixture and a signed catalog with public-key-only host configuration. The signed host
  also refused the unsigned fixture and retained its readable fallback.
- 8 Dokka adapter tests, real Dokka generation (including cached offline generation), and
  15 browser assertions on two independent embeds against the running server.
- TypeScript checks, the 23-page documentation build and attribution checks passed.

See the [visual evidence](../images/component-publications/README.md),
[`web/core/HOSTING.md`](../../web/core/HOSTING.md),
[`samples/component-preview/PUBLICATION.md`](../../samples/component-preview/PUBLICATION.md)
and [the Dokka sample](../../samples/dokka-preview/) for reproduction and limits.
CI additionally runs the complete Livery build and existing A2UI/React compatibility matrix.
The server's full CI remains the integration gate for its released dependency graph.

No production deployment, real Bluesky post, Android device share-sheet test, editor exporter
enablement, native Heron RC renderer or shared live-data backend is claimed. Those remain
separate milestones; the committed fixtures and integration commands make them reproducible.

The existing Livery 0.7.0 release is still draft: its build ran out of compiler heap and its
npm job found no `NPM_TOKEN`. This slice aligns release compiler limits with the passing CI
configuration and adds a viewer ZIP asset. Configuring npm publishing credentials remains
an operator action; no credentials, release tags or production deployments are changed here.
