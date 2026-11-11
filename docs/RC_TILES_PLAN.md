# Shareable Remote Compose components: delivery plan

Status: **Staged delivery; first wave merged.** See the
[first-wave report](handovers/WAVE_1.md) and
[public hosting and documentation slice](handovers/WAVE_2.md) for delivery and validation.
This is a delivery plan, not a supported publication API or AT Protocol standard.
Source inspection: 2026-11-08. “RC tile” remains a provisional format name.

Implementation assignments: [parallel handover notes](handovers/README.md), with owners,
shared interface checkpoints, dependencies and acceptance checks.

## Execution gate

Confetti work waits until the relevant upstream PRs land, per the user's direction. Do not
implement against or continue investigating its unmerged stack now. Reinspect merged code
before starting Confetti content or public-session integration. The snapshots below are
historical evidence and may no longer describe current integration wiring.

Meanwhile, use Livery's existing quantity-stepper sample to develop the shared fixture and
standalone web/native paths. Heron incoming sharing is independent. This is a development
fixture substitution; Confetti remains the intended real product demonstration.

## Product: Confetti components, interactive previews and shared sessions

Use Confetti's existing Remote Compose work as the first real content and native application.
Publish an interactive component preview at `preview.coo.ee`, embed it in documentation, and
share its public URL to Bluesky. Heron tests rendering the same published component natively
inside a social feed. `ui.coo.ee` supplies an authoring and publication entry point.

There are two related user journeys:

1. **Share a component preview.** A developer shares a KotlinConf session-card design with
   example inputs. A reader changes theme or example data, tries its controls and sees actions.
   Interaction state belongs to that reader's preview. This is useful in API documentation,
   design review and social posts about the component.
2. **Share a conference session.** An attendee shares an actual public session using that
   design. Recipients see its title, speaker, time and room, can open the session, and later can
   receive public schedule updates. Personal bookmarks remain the recipient's own account data.

The first implementation proves the component-preview journey, then adds public session
resolution. These share a player and publication mechanism but have different state and action
semantics. Neither requires a synthetic poll service.

“Live” has three separate meanings: animation and local interaction; following newly published
component revisions; and refreshing public conference data. Implement and label them separately.
Default release documentation and social shares to a pinned design revision. Offer an explicitly
labelled following channel for previews later. A preview must never publish every private edit
implicitly, and one reader's clicks must not alter another reader's preview.

## Evidence from Confetti's draft PRs

These are draft experiments, not released Confetti features. The stack builds on earlier layers;
implementation must coordinate with that stack and its author, not assume it has merged.

| Draft | Relevant work |
| --- | --- |
| [#2008](https://github.com/joreilly/Confetti/pull/2008) | `ConferenceComponents` abstraction, typed contracts and optional Android `:livery` integration through `sduiDebug`, with native fallback |
| [#2009](https://github.com/joreilly/Confetti/pull/2009) | Base catalog: headers, session cards, speaker cards and promotions; captured RC, theme packs and a static publication workflow |
| [#2010](https://github.com/joreilly/Confetti/pull/2010) | droidcon designs, fonts, themes and authored launcher/Wear targets |
| [#2011](https://github.com/joreilly/Confetti/pull/2011) | KotlinConf designs, fonts, themes and authored launcher/Wear targets |
| [#2012](https://github.com/joreilly/Confetti/pull/2012) | Architecture, findings and remaining integration checks |
| [#2013](https://github.com/joreilly/Confetti/pull/2013) | Optional shader header experiment with capability-based fallback |

Code and findings inspected at #2012 head
[`8a7fb831`](https://github.com/yschimke/Confetti/tree/8a7fb831c40ad5a504796025b9fb42c1625011c4).
The shader layer's description was inspected separately. Recheck current heads before coding.

Concrete seams:

- `livery-catalog/` captures Kotlin RC designs and contains base, droidcon and KotlinConf catalogs.
- `livery-contracts/` defines `confetti/session@1`, `conference`, `speaker` and `promo` contracts.
- `livery/.../ConferenceSlots.kt` binds a session's title, speakers, room, formatted time, live
  state and personal bookmark state. `open` and `bookmark` call the surrounding app's callbacks.
- Session identity is supplied by the app (`card.session.id`), not encoded in the session
  contract's data. A portable share needs conference/session identity in its resource descriptor;
  carrying the displayed values alone cannot recreate those callbacks in Heron.
- Android is integrated only in `sduiDebug`. Other platform roots still use native components.
  Authored widget targets are not proof of integrated widget hosts or browser compatibility.
- The inspected experiment permits debug catalog use; production signing/trust remains work.
  Reuse the source and publisher, not an assumption that an existing debug channel is trusted.

Use the non-shader KotlinConf session card first. Custom fonts, document profile support and
size constraints are explicit web compatibility checks. Shaders remain a later capability demo.
Do not claim existing Kotlin captures can round-trip into UI Builder: importing a published
artifact for preview and authoring an editable design are separate capabilities to investigate.

## Existing Livery foundation

- `livery-client` and `livery-protocol`: catalog/resource verification, `ContractBinder`,
  `TemplateSelector`, host action gates and fallback. Keep one shared implementation of each.
- `livery-compose`: Compose player and named-value updates without reloading unchanged bytes.
- `livery-web` and `web/a2ui/src/player.ts`: existing KMP/Wasm pipeline and web RC player. Extract
  a standalone entry point; do not require A2UI or create a second TypeScript binding engine.
- `livery-publish`, `livery-render`, `livery-uibuilder`: publication, fallback images and
  revision-selected UI Builder export. Public hosting and author-facing sharing remain work.
- `samples/two-way` and `QuantityStepper` (`livery-capture-cmp`'s `samples/SampleDesigns.kt`): a useful small diagnostic fixture. Its `setQuantity`
  action writes a validated value back to a host-owned input; no remote application service is
  necessary. Use it to isolate player failures, not as the headline product demo.

The differentiator is the same published RC component in a Compose host and on the web.
Web Tiles already works through web content on mobile. Learn from its embedding and fallback
model; do not claim RC is required by Web Tiles or that the formats are compatible.

## Flow: a page in ui.coo.ee to a Bluesky post

All UI labels and URL paths below are proposed, not existing endpoints.

1. **Choose what to share.** On a UI Builder page, select the component and current revision,
   example inputs, theme and framing. “Share preview” opens a preview of the public result.
   For a Confetti session share, select its public conference/session reference instead of
   treating the author's current app state as the payload.
2. **Publish a public preview.** Export the chosen RC revision, validate its contract/resources,
   produce a trusted publication and render its static thumbnail. Store only selected public
   data. Publication returns an immutable revision URL such as
   `https://preview.coo.ee/p/<publication>/r/<revision>`. Reuse this publication for repeated shares.
   A Kotlin-authored Confetti catalog can enter the same pipeline through CLI/CI before editor
   import/authoring support exists.
3. **Prepare the public landing page.** Return server-rendered title, description, canonical URL
   and OpenGraph image without login or client-side JavaScript. Serve a lightweight interactive
   viewer and a versioned descriptor for admitted native hosts. The page and image must be
   publicly fetchable by link-preview services. Never share an editor session or export token.
4. **Offer destinations.** “Share to Bluesky” opens
   `https://bsky.app/intent/compose?text=<URL-encoded text and public URL>`.
   “Share…” uses the browser/OS share sheet where supported, allowing the user to choose an
   installed client. “Copy link” remains the fallback. Keep suggested text short enough for the
   post limit. Do not assume the Bluesky intent opens Heron or that every client accepts it.
5. **Review in the chosen app.** The app handles sign-in, editable post text and creation of its
   normal external link card. Where required, the user adds the link card. An intent pre-fills
   text; it cannot force an embed or guarantee publication. The user presses Post in that app.
   UI Builder needs no Bluesky account token for this flow and must not report “posted” merely
   because the share sheet closed.
6. **Read the post.** A normal client shows its ordinary `app.bsky.embed.external` card and opens
   the web preview. An opted-in Heron recognises the supported public URL, resolves and validates
   the descriptor and renders RC inline. A missing embed, unsupported design, moderation block
   or resolution failure preserves ordinary text/link/card behaviour.

The Bluesky post carries text, URL and normal external-card metadata, not executable RC or editor
credentials. The external card's stored thumbnail/metadata does not automatically update after
publication; following live RC data in a supporting host is separate from that static fallback.
There is no need for a custom AT Lexicon or direct posting API to deliver this first flow.

**Heron has two independent integration tasks:** receiving a share into its composer, and
rendering a shared URL in its timeline. At the inspected revision its Android manifest declares
VIEW deep links but no SEND text share target, and `MainActivity` handles `intent.data`, not
`EXTRA_TEXT`. Add/test incoming text sharing and routing to compose; do not assume it already
works. Test cold start, warm start, signed-out continuation, cancellation, and ordinary pasted
links. Copy/paste or the Bluesky web composer can unblock the first rendering demo.

## Documentation embedding

Start with a standard preview link in KDoc and an HTML embed on a small documentation page.
A proposed Dokka plugin maps recognised preview links or build-time symbol/sample mappings to
inline viewers in generated HTML. Keep the link as fallback; do not invent a mandatory KDoc tag.

- A component's `@sample` can still show Kotlin source. It does not itself export or execute an
  RC component. A separate capture/export task produces versioned artifacts and a mapping from
  documented sample/symbol to publication URL.
- Initially embed the hosted viewer in an iframe. Validate framing headers, parent CSP, sizing,
  focus, accessible labelling and lazy loading. Add a direct web component only if a host needs it.
- Offer example selection, theme, width, reset and an action log. In preview mode, bookmark can
  toggle a local example value and `open` can report its event. Label this as demonstration;
  it does not save a real Confetti bookmark or navigate without host approval.
- A published RC document does not contain arbitrary Kotlin callbacks. The preview host supplies
  a small, explicit action adapter; use existing `set` semantics where applicable. Do not ship
  arbitrary author scripts to reproduce the original app.
- Pin release docs to the matching component revision and include a thumbnail/link for offline
  or noninteractive output. Retention, expiry and revocation need policy; pinning a URL alone
  cannot promise indefinite availability.
- IDE KDoc popups are a separate host. Plan for a link there initially, not an assumption that
  iframe/JavaScript embeds supported by generated HTML will execute in the IDE.

## Publication, resource and state boundaries

The minimal experimental descriptor identifies a publication/revision, catalog/design/contract,
public fallback and optional public resource reference. Reuse catalog integrity and capability
checks rather than defining a new trust model. Start with allowlisted origins and configured
publisher keys; no arbitrary endpoint or action discovery is needed for the first demo.

Keep these concepts separate:

- **Published component:** immutable design, contract, permitted examples and assets.
- **Preview session:** local per-viewer state, resettable, not a globally shared resource.
- **Public conference session:** stable conference/session identity, a public data source and
  revisioned data. The Confetti adapter owns this mapping, not Livery's core.
- **Personal state:** bookmarks and authentication belong to the viewer's authorised host/account.

For a real shared session, initially allow “open session” through a verified public URL/deep link
with web fallback. Verify Confetti's actual link routing before selecting that URL. Heron cannot
invoke Confetti's in-process callback. Real bookmarking should open Confetti or require a later
explicit authorised integration; a Bluesky login alone grants no Confetti permissions.

Public session data may refresh without republishing the layout. Read from Confetti's existing
public data source where feasible, with a small adapter if needed; do not create a second agenda
backend. Store no private bookmarks in a share descriptor. The adapter needs stable IDs and
structured timing/timezone data to recompute status; a formatted display time is insufficient.

## Stages and exit gates

| Stage | Work | Exit gate |
| --- | --- | --- |
| 0. Portable Confetti component | Publish one non-shader session-card revision and example; reuse existing Android integration; run it in standalone web | Both players render the real artifact/fonts and handle preview actions; unsupported capabilities have useful fallback |
| 1. Public preview and docs | Public viewer, thumbnail, descriptor, local preview actions, simple HTML embed and initial Dokka integration | A developer can link/embed a versioned interactive sample; readers have isolated state and need no login |
| 2. Share and read in Bluesky/Heron | UI Builder publish/share flow, compose intent/share-sheet handoff, Heron incoming-share and external-card rendering | One UI Builder page leads to a user-reviewed post; ordinary clients show a card, Heron plays it natively |
| 3. Share actual Confetti sessions | Public resource adapter, canonical session identity, open-session action, controlled refresh | Recipients see current public session data; personal bookmarks are never copied or silently written |
| 4. Broaden and harden | Second component, following publication channels, host performance/accessibility, independent integration | More than one component works without app-specific changes in Livery core |
| 5. Experimental AT adapter | Community Lexicon for proven publication/resource references, identity and discovery | HTTPS and AT references resolve consistently, with trust, deletion and migration documented |
| 6. Standardisation | Minimal spec, fixtures, independent publishers/hosts and community review | Interoperability evidence and agreement on compatibility, not just a reference implementation |

Stages are integration gates, not a requirement to finish every docs feature before starting
Heron. The share/publication, documentation and native streams can run concurrently once the
first artifact and descriptor are agreed. UI Builder editing of imported Confetti designs must
not block sharing a Kotlin/CI-published preview.

## Streams that can start now

| Stream | First concrete deliverable | Location/boundary |
| --- | --- | --- |
| Confetti content | One pinned session-card artifact, examples, action adapter and public-resource mapping | Confetti draft stack; keep its contracts and app concepts there |
| Web preview | Standalone viewer, local state/reset/actions, theme/size controls | Livery web core/player plus preview host; preserve A2UI wrapper compatibility |
| Publish and share | Public revision URL, static card metadata, Share to Bluesky/Share/Copy link | Livery publisher + `compose-preview-server`; `compose-ui-builder` UI/export adapter |
| Docs integration | One KDoc-linked sample rendered interactively in Dokka HTML, with fallback | Optional Dokka/build adapter; no Dokka dependency in Livery runtime |
| Heron | Native external-link preview plus incoming text-share support | Heron repository/composer/timeline layers using `livery-compose` |
| Host conformance | Shared artifact/action fixtures and capability, accessibility and lifecycle checks | Livery conformance/player tests and app integration tests |

AT research can continue alongside this work, but new public Lexicons are not a first-demo
prerequisite. Do not create a generic remote-action service, poll backend or mandatory new core
module. Extract an optional preview/publication adapter only when the two hosts need it.

## Heron and cross-host acceptance checks

Inspected Heron revision:
[`dfe4ba9`](https://github.com/tunjid/heron/tree/dfe4ba961a825fe23d6a788c1edcc9d75dd4f121).

- Its `PostEmbed` dispatches external cards to `PostExternal`; retain existing title, thumbnail
  and link as fallback. Resolve descriptors outside composition through the repository layer.
- Preserve sensitive-content handling and noninteractive composer previews. Hidden content must
  not activate actions or subscriptions, and a button tap must not trigger the enclosing link.
- Keep preview state keyed to the view/session, not globally keyed by public resource. Public
  read subscriptions can be deduplicated without accidentally sharing local example state.
- Suspend background/offscreen work; render cached state without waiting for network. Test
  scrolling, repeated cards, process restart, rotation and disconnection.
- Test unsupported documents, invalid signatures, expiry/revocation, fonts and bounded resource
  use. Pinning does not bypass publisher trust policy. Keep non-Android Heron targets building.
- Measure frame time, memory, download size and network activity. Check screen readers, text
  scaling, focus and touch targets. Native RC rendering does not prove native accessibility.
- Test the full share matrix: desktop browser to Bluesky web; Android browser to available
  clients; Heron cold/warm/sign-in paths; pasted URL; cancelled composer; failed publication;
  failed thumbnail scraping; reader without RC support. No false “posted” confirmation.

## First implementation slices

1. **Confetti sample publication + web compatibility spike:** use an existing captured session
   card, preserve attribution/assets, demonstrate preview actions with isolated local state.
2. **Standalone Livery web host:** reuse the existing KMP/Wasm validation path and player outside
   A2UI, exposing the small public viewer needed by preview.coo.ee and docs.
3. **Publication/share flow:** versioned public artifacts and metadata, then UI Builder's
   preview/share controls and standard Bluesky handoff. No direct account posting in this phase.
4. **Heron integration:** split incoming-share/composer support from native feed rendering so
   either can be tested independently using ordinary URLs.
5. **Dokka adapter:** map a documented sample to its published revision and insert a viewer with
   source and link fallback. Treat release-version matching as a build check.
6. **Confetti public-session adapter:** connect the shared design to real public session identity
   and updates, with an honest open-session/bookmark policy for each host.

Code changes run affected tests and required repository CI. Shared binding, selection, resources
or trust changes update normative `PROTOCOL.md`, conformance fixtures and Swift together under
`AGENTS.md`. This planning change requires documentation/link checks only.

## Eventual AT Protocol scope

Standardise the portable publication/resource reference, identity, capability and fallback
behaviour demonstrated by these products. Specify how a DID relates to a permitted publisher,
Livery signing keys and action origins; AT authentication does not replace blob/manifest checks.
Map AT CIDs to content references deliberately. Public repository records must not contain
editor credentials, personal bookmarks or private viewer state. Keep transient preview state
local and high-frequency data in its resource service unless an AT-native product requires writes.

A custom Lexicon is a community schema, not automatic support in Bluesky clients or a new core
protocol feature. Keep HTTPS fallback, use an experimental owned namespace initially, and seek
independent host/publisher implementations before claiming stability. AT OAuth is introduced
only for actions needing it; rendering a shared component never grants account-wide access.

## References

- [Livery architecture](DESIGN.md), [normative protocol](PROTOCOL.md), [A2UI adapter](A2UI.md)
- [Confetti findings and implementation](https://github.com/yschimke/Confetti/blob/8a7fb831c40ad5a504796025b9fb42c1625011c4/livery-catalog/README.md)
- [Dokka plugins](https://kotlinlang.org/docs/dokka-plugins.html)
- [KDoc syntax and samples](https://kotlinlang.org/docs/kotlin-doc.html)
- [Bluesky compose intent](https://docs.bsky.app/docs/advanced-guides/intent-links)
- [Bluesky posts and external cards](https://docs.bsky.app/docs/tutorials/creating-a-post)
- [Web Tiles](https://dasl.ing/tiles.html)
- [Publishing AT Lexicons](https://atproto.com/guides/publishing-lexicons)
