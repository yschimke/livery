# Livery design

Livery is a toolkit for **server-driven Remote Compose (RC) templates**: values-free documents that
an app downloads, caches, picks between, fills with data and plays, in an app screen, a launcher
widget, a Wear widget, a web page or a desktop window. A design changes without an app release.

It started as the card design server inside
[homeassistant-remotecompose](https://github.com/yschimke/homeassistant-remotecompose) and is
being extracted so any app can use it. It assumes nothing about Home Assistant, about UI Builder,
or about how a template was authored or where it is hosted.

## What it has to serve

| Product | Who decides what shows | Where the data comes from | What matters most |
| --- | --- | --- | --- |
| **Interactive ads** | an ad / decision server | the decision response | signatures per publisher, size and op budgets, measurement, never another design than the one decided |
| **Growth features** (promotions, nudges, onboarding) | rules shipped in the catalog, or a server | the rule or decision | works offline, schedules, sticky rollouts, frequency caps |
| **Feeds** | the app's feed API | each feed item | few templates, many items, data-only payloads, prefetch per page |
| **Dynamic content** (Home Assistant cards) | the app, per slot | the app's own live model | live updates without re-encoding, skins people install, widgets |

One model covers all four because they differ only in **who picks the template** and **who
supplies the data**. Everything else — fetching, verifying, caching, selecting, checking data,
playing, actions, events — is shared.

## Concepts

```
Catalog channel ── manifest.json (signed) ──┬── contracts   the data/action interface
                                            ├── designs     a look a person or a rule picks
                                            ├── templates   one .rc per design × target
                                            ├── themes      named values (colours, dims)
                                            └── placements  local growth rules
                    blobs/<sha256>.rc         content addressed, immutable
                    previews/<sha256>.webp    pictures of designs, before download
```

- **Contract** (`promo/banner@1`). What a template reads and emits: typed inputs (`string`,
  `int`, `long`, `float`, `bool`, `color`, `image`) with limits (`maxLength`, `min`, `max`),
  lists (flattened to `items.count`, `items.0.title`, … because RC named values are flat),
  actions by **kind**, example datasets, and who supplies the data (`App`, `Decision`, `Feed`).
  Templates pin `id@major`; a minor revision only adds optional inputs and actions. The contract
  is the one thing an app and a template author must agree on.
- **Design** (`banner-classic`). One look for one contract, as a picker shows it: title, author,
  variant, tags, previews, an `install` policy, and the design it was forked from. A choice stored
  by design id keeps working as the catalog adds targets.
- **Template** (`TemplateEntry`). One playable, values-free document for one **target**:
  `surface` (`in-app`, `launcher-widget`, `wear-widget`, `wear-app`, `web`, `desktop`, `tv`,
  `snapshot`), `rcApiLevel`, op `profiles`, the `size` range it lays out for (it carries its own
  size tiers), `theme` (`host` or `baked:<mode>`) and an optional fixed `container`
  (`wear:small`). The publisher also records the named values it reads and the actions it emits.
- **Theme pack.** A theme as data: named values pushed beside every template's own. Installing one
  costs no download. Typefaces can't be bound in a document, so they belong to a design variant.
- **Placement.** A named slot in the app (`home.top`). A **decision** fills it: a template
  reference plus data plus tracking, from a local rule or a remote decision service.
- **Feed page.** Items, each a template reference plus data. Templates are few and installed or
  cached; items are data only.

### Variants, themes and designs

These combine freely and are deliberately separate: a **variant** is a layout language
(`compact`, `hero`); a **theme** is colours and dimensions pushed as values; a **design** is what
a person picks and may be either. `default` always exists for a contract that has designs.

## Data: three ways into one document

A template never contains a user's values. The client fills its named values in one of three ways:

1. **Live push** (in-app players): the player draws the template and the host pushes values on
   every change. Animations play on change only; the first frame already shows current data
   (values are seeded before the first frame, never from an effect).
2. **Instantiate** (launcher widgets, Glance Wear widgets, snapshots): a host that plays bytes it
   is handed can't receive pushes, so the client writes the values into the document's
   named-value initial values (`rc-player-edit`'s `hardcodeNamedValues`, then
   `collapseConstantStateLayouts` and `optimize`), and hands over the result.
3. **Server instantiate** (dumb clients, email, notifications): the same step on a JVM server.

Whatever the source, data passes one gate, `ContractBinder.bind(contract, data)`, which refuses
unknown keys, wrong types, out-of-range numbers and over-long strings, and flattens lists. The
same gate runs on the device, in the publisher (examples, placement payloads), on a server, and
in agent tools, so "valid" means one thing everywhere.

**Actions.** A template emits a host action by name with a flat payload. The contract gives each
action a **kind**; the client routes kinds to handlers the app registers. Built-in kinds:
`open-url` (allowlisted hosts only), `navigate` (deep link or route), `dismiss`, `track`,
`refresh`. Anything else (`app:toggle`, a Home Assistant service call) is the app's own handler.
An undeclared action, an unregistered kind or a disallowed URL is dropped and reported. A
malicious or broken design can at worst trigger something the app already allowed for that slot.

**Events.** `Impression` (first frame), `Viewable` (half on screen for a second), `Action`,
`Dismiss`, `Fallback` (and why), `Error`. An `EventSink` routes them to the app's analytics; a
decision's or feed item's `tracking` URLs are fired by the app's own network stack.

## Client

### Sources: where a catalog lives

A source is **one manifest URL**; everything else is resolved from the manifest's `blobBase` and
`previewBase`, absolute or relative to it. So any host works:

| Source | Manifest | Blobs |
| --- | --- | --- |
| Static host (Pages, S3, CDN) | `<base>/manifest.json` | `<base>/blobs/<sha>.rc` |
| **GitHub release** | `releases/latest/download/manifest.json` or a tag | flat assets, `blobBase` = the release's download URL |
| Design server channel | `<server>/v1/catalogs/<id>/channels/<channel>/manifest.json` | same layout |
| External design catalog | an adapter that maps its API to this manifest | its own URLs |
| Bundled (app assets) | `asset://<root>/manifest.json` | same layout, the last fallback |

GitHub release downloads send no CORS headers, so a browser client reads a Pages mirror or a proxy.

### Trust

- Every blob is checked against the manifest's size and SHA-256.
- The manifest has a **detached Ed25519 signature** (`manifest.sig.json`) over its exact bytes, so
  no canonical JSON is needed. The app embeds the public keys it trusts.
- A key can be **limited to contract prefixes**: an ads partner's key signs `ads/` templates and
  nothing else, while the app's own key signs everything.
- A monotonic `serial` per catalog and channel defeats replay of an older manifest (to hide a
  revocation); `expiresAt` turns a stale manifest from fresh into stale; `minClient`,
  `revoked` designs and templates are the kill switch.
- `AllowUnsigned` exists for development and preview channels only.
- A document plays inside the RC player: no code, no network. Images are resolved by the host's own
  loader, from asset ids or allowlisted URLs.

### Cache, refresh and fallback

```
<store>/livery/<source id>/manifest     the last good manifest
<store>/livery/<source id>/meta         etag, accepted serial, fetched at, failures, next attempt
<store>/livery/<source id>/installed    installed design ids
<blobs>/<sha256>.rc                     shared by every source
```

- Rendering never waits on the network: `resolve` reads only the disk.
- `refresh` runs from the platform scheduler (WorkManager on Android, BGTaskScheduler on iOS, a
  service worker or page load on the web), on start, and when a choice changes. It revalidates
  with ETag within `ttlSeconds`, backs off (1 min, 1 h, 4 h, 24 h, honouring `Retry-After`), and
  downloads only what install policies ask for, for every target the app serves (so a widget
  works the moment it's placed).
- Fallback per slot: fresh cached → stale cached (not revoked, within a grace period) → bundled →
  the app's native fallback. A chosen design that isn't downloaded draws the automatic one, never
  nothing.

### Install policies

| Policy | Downloaded | Evicted | Used for |
| --- | --- | --- | --- |
| `Always` | every refresh | never | the built-in look, hot placements, feed item templates |
| `OnRequest` | when installed | when uninstalled and pruned | skins and themes a person picks |
| `OnDemand` | when first needed | LRU, not while visible | ad creatives, rarely used feed templates |

**Automatic selection only considers `Always` and installed designs**, so a skin nobody installed is
never drawn by surprise. A **preview** downloads one template of a design into the evictable
cache without installing it, so a picker can play it live (with the contract's examples or the
person's own data) before they commit. Pictures in the manifest show it before any download.

### Selection

`TemplateSelector.select(manifest, query)` is pure and the same on every platform. Given a
contract, the player's capabilities (surface, max RC API level, profiles, whether it can push,
themes, container, byte budget), an optional size, design, variant, theme and tags, it returns the
chosen design's richest template the player can draw, with the tightest size fit, else the
preferred variant's, else `default`'s, else any automatic one.

### Deciding what a placement shows

- `LocalRulesDecider` evaluates the manifest's placement rules on the device: schedule, platform,
  locale, app version, segments (included and excluded), a sticky rollout bucket (FNV-1a of
  experiment and an app-scoped id), and a frequency cap; the highest priority wins. Targeting data
  stays on the device.
- A remote decider is the app's own call to its ad or promotion server, answering a `Decision`.
- `FirstDecider` chains them (a paid campaign first, then house promotions).
- `SlotPlanner.forDecision` refuses to draw a decision in any design other than the one decided.

## Platform libraries

The core (`livery-protocol`, `livery-client`) is Kotlin Multiplatform and platform-free. Players
and schedulers are per platform:

| Module | Platform | Does |
| --- | --- | --- |
| `livery-protocol` | KMP (JVM, Android, iOS, Wasm) | the wire types ([PROTOCOL.md](PROTOCOL.md)) |
| `livery-client` | KMP | sources, trust, cache, selection, install, preview, resources, decisions, feeds, actions, events; HTTP through **Ktor** (OkHttp on the JVM and Android, Darwin on iOS, `fetch` in the browser) |
| `livery-compose` | **Compose Multiplatform** (Android, desktop, iOS, Wasm) | `LiveryTemplate` / `LiverySlot` over rc-players' Compose player: values seeded before the first frame and pushed live, actions through the router, impression and viewability events, catalog fonts and images through the player's loaders, `rememberDesigns` / `DesignPicker` |
| `livery-android` *(planned)* | Android | the AndroidX View/Compose player, WorkManager refresh, file stores, image loading |
| `livery-launcher-widgets` *(planned)* | Android 16+ | a `LiveryWidgetProvider` base: per resize or data change, resolve the `launcher-widget` template for the cell, instantiate it with the app's data, hand it to `RemoteViews` draw instructions, map host actions to `PendingIntent`s; a `RemoteViews` fallback for older launchers |
| `livery-wear-widgets` *(planned)* | Wear OS | Glance Wear: `wear-widget` templates per container (`wear:small`, `wear:large`) with the container's padding and corner radius as named values, instantiated per widget; blobs synced from the phone over the Data Layer as content-addressed assets, or fetched by an LTE watch; bundled fallbacks on the watch |
| **`Livery` / `LiveryUI` Swift package** | iOS, macOS, watchOS, tvOS | a **native SwiftPM package** at the repository root, not a Kotlin export: Codable protocol types, the client on **`URLSession`** and **CryptoKit** Ed25519, file stores, decisions, and SwiftUI `LiveryTemplateView` / `LiverySlotView` over any player. The adapter for rc-players' pure Swift `RcNativePlayerUIKit` (`swift/Integrations/RcNativePlayer`) is a file an app adds beside its own player dependency, so depending on Livery never pulls in a player or the Compose XCFramework |
| `@livery/web` *(planned)* | browsers | TypeScript client (fetch, `crypto.subtle`, Cache API / IndexedDB) and a `<livery-template>` element over rc-players' TypeScript player |
| `livery-publish` | JVM CLI | validate a source tree, write a static or GitHub-release catalog, sign, verify |
| `livery-server` *(optional, planned)* | JVM (Ktor) | channels with upload-what-changed, mirrors, previews, decisions, feeds, on-request instantiation, agent sessions |
| `livery-mcp` *(planned)* | JVM | agent tools over any catalog (see below) |

**Keeping ports honest.** The Swift and TypeScript clients are ports of a small surface
(selection, binding, trust, URL resolution), pinned to the Kotlin one by shared **conformance
fixtures** in [`conformance/`](../conformance) ([PROTOCOL.md, Conformance](PROTOCOL.md#conformance)): manifests, queries, datasets, and the expected selection, bindings
and refusals. Every implementation runs them in CI; a change to the Kotlin behaviour regenerates
the fixtures, and a port that disagrees fails.

## Resources: images and fonts

A document never fetches anything; every byte it draws comes through the client
([PROTOCOL.md §6](PROTOCOL.md#6-resources)).

- **Catalog assets.** Images and fonts a design needs are content-addressed `assets` in the
  manifest, downloaded and verified with the template that lists them; a template is not handed
  out until all of them are on disk, so a widget or an offline screen never draws a fallback font
  or a missing picture. Fonts carry family, weight, style and an SPDX licence; a template's `fonts`
  names catalog families, `google:` families (a downloadable-font resolver the app opts into) or
  `system:` ones.
- **Images in data.** A dataset's image is `asset:<sha256>`, `https://…` or `app:<key>`; anything
  else is refused by the contract gate. A document draws a data-bound image from
  `livery:value/<input>`, and the client resolves it.
- **Image proxy.** Remote images go through the catalog's `imageProxy` (a URL template with the
  source, size and format, and the source hosts it serves): one host to trust, sized for the slot,
  formats normalised, cookies and tracking stripped. Without a proxy, only hosts the app allowlists
  are fetched. This is what lets an ad or a feed item carry a picture without the device talking to
  the advertiser.
- **Players.** On Compose, `LiveryImageLoader` and `LiveryTypefaceLoader` plug into the player's
  image and typeface loaders; on iOS the same goes through the native player's resource and font
  resolvers.

## Authoring and hosting are plugins

Livery takes **`.rc` bytes and a sidecar**, from anything:

- Kotlin capture code (`@RemoteComposable` content captured at build time, as Home Assistant's
  library is);
- UI Builder's `.uid` → RC authoring JSON → `remotecompose-json` → `.rc` export;
- an agent writing RC authoring JSON directly, compiled with `remotecompose-json`;
- a hand-built or third-party document.

The source tree:

```
catalog.json                          id, channel, ttl, expiry, minClient
contracts/*.json                      Contract
designs/<id>/design.json              Design
designs/<id>/<name>.rc + <name>.json  a template and its target, tags, reads, emits
designs/<id>/previews/<theme>[~<example>].png|webp
placements/*.json                     PlacementRule
themes/*.json                         ThemePack
```

`livery build` refuses a catalog with problems instead of publishing it: unknown contracts, a
template reading a value or emitting an action its contract doesn't declare, examples or
placement payloads that don't bind, duplicate ids, two templates for one target. A pluggable
`inspect` hook adds deep checks (op profile, byte budget, decoded named values) where the RC
libraries are available.

Hosting is just files: `livery build --github owner/repo --tag catalog-12` writes flat assets
for `gh release upload`; the default layout fits any static host or a server channel.

## AI workflows

Livery doesn't assume one workflow. It gives every workflow the same three tools — **the contract,
the gate and a render** — and a place to put a draft that is not yet trusted.

| Workflow | Agent produces | Gate | Reaches users by |
| --- | --- | --- | --- |
| **Repository + CI** | template source or RC JSON in a PR | `livery build` in CI, previews in the PR | the PR's channel (`pr-123`) for reviewers' apps, then `main` on merge |
| **Live co-editing** (Home Assistant's agent sessions) | drafts in a session with a person | contract + target check per draft, rendered with the person's real data in their app | the person accepts; published privately as their design |
| **Campaign generation** (growth, ads) | data only: copy, images, targeting rules for existing contracts | `ContractBinder` + a render of every target | a reviewer approves; rules or decisions ship, templates stay signed |
| **Runtime personalisation** | data per request inside a contract (copy, ranking) | the decision server validates against the contract before answering | the decision response; the client gates it again |
| **On-device** | a choice among installed designs, or data | the same gate on the device | immediately; nothing new is downloaded |

Rules that hold in every workflow:

- **An agent never ships unreviewed bytes to production slots.** Drafts are unsigned and play only
  in preview channels or a session's own preview. Production manifests are signed by a key the
  agent doesn't hold.
- **Prefer data over documents.** Generating copy for a reviewed template is far safer, cheaper and
  easier to review than generating a template.
- **The agent sees what the person sees**: a render with the person's or the contract's example
  data, on each target, at each size tier.

`livery-mcp` (planned) exposes this to any agent: `list_contracts`, `get_contract`,
`list_designs`, `validate_template`, `validate_data`, `render_preview` (template + data or example
+ target → PNG), `compile_rc_json`, `submit_draft`, `await_feedback`, `send_message`,
`set_status`, `publish(channel)`. Sessions follow the relay Home Assistant built: both sides
connect out, long-polls of at most 25 s, no inbound connection to the app or its backend, and a
session capability per scope. An authoring tool's own MCP server (UI Builder's `ui_builder_*`) is
one producer among others.

## Roadmap

1. **Core**: protocol, KMP client, publisher, sample catalog, tests. *Done.*
2. **Protocol spec and conformance fixtures**, including signing and resources. *Done.*
3. **`livery-compose`** and the **Swift package**. *First cut done*: the Swift player adapter is
   compiled only in an app that adds it; the native player's image and font resolvers are not
   wired yet.
4. **TypeScript port** over the same fixtures, so a browser can play the sample catalog.
5. **`livery-android`**: the AndroidX View player, WorkManager refresh.
6. **Widgets**: launcher instantiation and the Wear widget host, built on `rc-player-edit`.
7. **Home Assistant migration** ([plan](migrating-homeassistant-remotecompose.md)).
8. **`livery-server`** (with an image proxy) and **`livery-mcp`**.

## Open questions

- Manifest size for large catalogs (Home Assistant's library is hundreds of MB of blobs): split
  the manifest by contract or surface, or keep one index plus per-contract pages?
- A delta for a feed of thousands of items is the app's API's business; should Livery define a
  `FeedPage` caching contract beyond `ttlSeconds`?
- Viewability rules differ per ad standard; the defaults here are the common 50% for one second.
- Remote Compose is alpha: which `rcApiLevel` and profile names to standardise on, and who owns
  that list (rc-players, or here).
