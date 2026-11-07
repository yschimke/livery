# Livery protocol, level 7

The rules every Livery client and publisher implements, whatever the language. The Kotlin types in
`livery-protocol` are the reference encoding; the files in [`conformance/`](../conformance) are the
reference behaviour, and every port must reproduce them (see [Conformance](#conformance)). "Must"
and "never" are requirements; anything else is advice.

## 1. Files

A **catalog channel** is a directory of files on any host:

| File | Content | Caching |
| --- | --- | --- |
| `manifest.json` | the index (§2) | revalidate: `ETag` / `If-None-Match` |
| `manifest.sig.json` | the detached signature over `manifest.json`'s exact bytes (§3) | with the manifest |
| `<blobBase><sha256>.rc` | a values-free Remote Compose document | immutable |
| `<assetBase><sha256>.<ext>` | an image or font (§6) | immutable |
| `<previewBase><sha256>.<ext>` | a picture of a design (§2.3) | immutable |

`blobBase`, `assetBase` and `previewBase` default to `blobs/`, `assets/` and `previews/`. Each is
an absolute URL or is resolved against the manifest's URL: a reference containing `://` is used as
it is; one starting with `/` replaces the path; anything else replaces the last path segment. A
GitHub release uses flat asset names, so its manifest sets all three to the release's download URL
(`https://github.com/<owner>/<repo>/releases/download/<tag>/`).

Every immutable file is named by the lowercase hex SHA-256 of its bytes. A client must check both
the size the manifest gives and the hash before using or caching it, and must treat a mismatch
as a failed download.

## 2. The manifest

UTF-8 JSON. Clients must ignore unknown keys; publishers may omit any key that has its default.

### 2.1 Top level

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `schema` | int | `1` | this document's version |
| `catalog` | string | — | stable catalog id; clients cache per catalog and channel |
| `channel` | string | `main` | `main`, `staging`, `pr-123`, …; a channel is a whole manifest |
| `serial` | int64 | — | strictly increasing per catalog and channel (§3.3) |
| `generatedAtEpochMs` | int64 | — | when it was built |
| `expiresAtEpochMs` | int64 | — | after this the manifest is stale (§3.3) |
| `ttlSeconds` | int64 | `3600` | how long a client may skip revalidation |
| `minClient` | int | `0` | the lowest protocol level that may use it; this document is level 7 |
| `blobBase`, `assetBase`, `previewBase` | string | see §1 | where files live |
| `contracts` | Contract[] | `[]` | §4 |
| `designs` | Design[] | `[]` | §2.3 |
| `templates` | TemplateEntry[] | `[]` | §2.2 |
| `assets` | AssetEntry[] | `[]` | §6 |
| `themes` | ThemePack[] | `[]` | named values pushed beside a template's own |
| `placements` | PlacementRule[] | `[]` | local decision rules (§8) |
| `experiments` | Experiment[] | `[]` | A/B tests over designs and placements (§8.1) |
| `imageProxy` | ImageProxy? | `null` | §6.2 |
| `provenance` | map<string,string> | `{}` | informational; clients never decide on it |

**Levels.** Level 1 is this document without §6 and the `assets`, `assetBase`, `imageProxy` and
`TemplateEntry.assets` / `fonts` fields. Level 2 added them. A level-1 client would ignore them and
hand out templates without their resources, so a publisher must set `minClient` to at least 2 for
a catalog in which any template lists `assets`. Level 3 added contract minors (`since`,
`requiresMinor`, and the query's `minor` in §5.1). A level-2 client ignores `requiresMinor`, as a
level-3 query without a `minor` does: an input its app doesn't fill draws the template's own
default, and an action it can't route does nothing. Whether that is acceptable is the publisher's
policy (§4.3): a strict catalog sets `minClient` to at least 3 when a template of a strict contract
has a `requiresMinor` above 0, so older clients keep what they had. Level 4 added experiments
(§8.1). A level-3 client ignores a placement rule's `arm` and would show an arm's rule to everyone,
so a publisher must set `minClient` to at least 4 for a catalog in which any placement rule has an
`arm`. Design arms need no level: a client that ignores them draws what the control arm does. Level
5 added secondary screens (§8.2): a level-4 client would drop every `present` tap and never show a
screen placement's rules, so a publisher must set `minClient` to at least 5 for a catalog with a
`present` action or a placement rule with a `presentation` or `screens`. Level 6 added designs
that aren't automatic (§2.3): a level-5 client would pick one like any other, so a publisher must
set `minClient` to at least 6 for a catalog with a design whose `automatic` is false. Level 7 added
`set` actions (§7.1): a level-6 client has no handler for the kind and would drop every one, so a
template's own controls would do nothing; a publisher must set `minClient` to at least 7 for a
catalog with a `set` action. A client must
reject a manifest whose `minClient` is above its level and keep using what it had (§3.3).

### 2.2 Template entries and targets

A template entry is one document for one target: `sha256`, `bytes`, `designId`, `contract`
(`id@major`), `target`, `tags`, `previews`, `revoked`, `reads` (the named values it reads),
`emits` (the actions it may emit), `requiresMinor` (the lowest contract minor an app must implement
to play it, §4.3; 0 when absent), `assets` (hashes of the images and fonts it needs), `fonts`
(the families it names) and `source` (a free-form string map of where the document came from, such
as `tool`, `design`, `revision` and `commit`, for tracing a version; clients never read it to
decide anything).

A target is `surface` (`in-app`, `launcher-widget`, `wear-widget`, `notification`, `wear-app`, `web`,
`desktop`, `tv`, `snapshot`, or an app's own), `rcApiLevel`, `profiles` (operation profiles the document stays
inside), `size` (inclusive dp ranges `minWidthDp`, `maxWidthDp`, `minHeightDp`, `maxHeightDp`; a
missing bound is unbounded), `theme` (`host`, or `baked:<mode>`) and `container` (a fixed host
container such as `wear:small`, or none).

### 2.3 Designs

A design is one look for one contract: `id`, `contract`, `title`, `description`, `author`,
`variant` (`default` if absent), `tags`, `previews`, `forkedFrom`, `revision`, `revoked`,
`install` and `automatic` (`true` if absent):

| `install` | A client downloads it | Automatic selection may pick it |
| --- | --- | --- |
| `Always` | on every refresh, for every target it serves | yes |
| `OnRequest` | only once the app or person installs it | only once installed |
| `OnDemand` | when a decision, feed item or slot first needs it | yes |

A design whose `automatic` is false is downloaded as its `install` says, but automatic selection
(§5.2) never picks it: it draws only when named (a chosen design, a decision) or picked by an
experiment arm (§8.1), as a season's design is. Design pickers don't offer it.

## 3. Signing and trust

### 3.1 Signature

`manifest.sig.json` is `{"alg": "Ed25519", "keyId": "<id>", "signature": "<base64>"}`. The
signature is Ed25519 (RFC 8032, pure, no pre-hash) over **the exact bytes of `manifest.json` as
served**: no canonicalisation, no re-encoding. `signature` is standard Base64 with padding (RFC
4648 §4). A publisher must not change `manifest.json` after signing it, and a host must serve
it byte for byte.

### 3.2 Verification

A client is configured with **trusted keys**: a `keyId`, the raw 32-byte Ed25519 public key, and
optionally **contract prefixes** the key may sign. A client must reject the manifest, and treat
it as a failed refresh, when any of these hold:

1. there is no signature (unless the client is in an explicit development mode that allows unsigned
   catalogs, which must never apply to production slots);
2. `alg` is not `Ed25519`;
3. `keyId` is not a trusted key;
4. `signature` is not valid Base64, or does not verify over the bytes received;
5. the manifest does not parse;
6. the key has contract prefixes and any `templates[].contract` or `designs[].contract` starts
   with none of them.

Rule 6 lets an app trust a partner's key for, say, `ads/` contracts only, while its own key signs
everything.

### 3.3 Freshness and rollback

- A client must store the highest `serial` it accepted per catalog and channel, and reject a
  manifest with a lower one, so that a host cannot replay an old manifest to hide a revocation. An
  equal serial is the same release.
- A client must reject a manifest whose `minClient` is above the level it implements.
- After `expiresAtEpochMs` the manifest is **stale**. A client may keep using it for a grace period
  (the reference client allows 30 days) and must report what it plays as stale. After the grace
  period it must stop using it for every kind of slot, including exact references (§5.3).
- `revoked` on a design or template entry withdraws it at once.

## 4. Contracts and data

A contract is `id`, `major`, `minor`, `inputs` (name → `InputSpec`), `lists` (name → `ListSpec`),
`actions` (name → `ActionSpec`), `examples` (name → dataset) and `dataSource` (`App`, `Decision` or
`Feed`). Templates bind `id@major`; a later minor may only add optional inputs, lists and actions
(§4.3).

`InputSpec`: `type` (`string`, `int`, `long`, `float`, `bool`, `color`, `image`), `required`,
`default`, `maxLength`, `min`, `max`, `role`, `description`, `since`. `ListSpec`: `item` (field →
`InputSpec`), `max`, `description`, `since`. `ActionSpec`: `kind`, `args`, `description`, `since`,
`placement` (a `present`'s, §8.2) and `input` (a `set`'s, §7.1).
`since` is the contract minor that added the input, list or action; 0 when absent.

### 4.1 Binding a dataset

A dataset is a JSON object. Binding it to a contract produces flat named values or a list of
problems; it must refuse rather than drop:

- a key that is neither an input nor a list is a problem;
- for each input: a missing or `null` value takes `default`; still missing and `required` is a
  problem; otherwise the value must have the input's type, where `int`, `long`, `float` and
  `bool` reject JSON strings, `int` and `long` take any JSON number with a whole-number value
  (`3`, `3.0` and `3e0` alike; beyond 2^53 only an integer literal), `int` must fit 32 bits, `string` must respect `maxLength`, numbers
  must respect `min` and `max`, `color` is `#RRGGBB` (opaque) or `#AARRGGBB`, and `image` is an
  image reference (§6.1);
- a list must be a JSON array of objects, at most `max` long; it binds `<list>.count` (int, the
  number bound) and `<list>.<i>.<field>` for `i` from 0, with each field checked as an input; an
  unknown field in an item is a problem.

The binding result is the same everywhere data enters: on the device, in the publisher (examples
and placement payloads), on a decision server, and in agent tools.

### 4.2 Named values in the document

A template reads its data as Remote Compose named values in the `USER` domain, under the flattened
names above. Theme roles are `theme.<role>`. A publisher must refuse a template whose `reads` names
a value its contract cannot produce.

### 4.3 Evolution

Within one major, a contract changes only in ways every template and data producer of that major
survives. A new minor may:

- add an optional input, list, list field, action or action argument, with `since` set to the new
  minor;
- loosen a limit (raise `maxLength` or a list's `max`, widen `min`/`max`), when every template still
  draws at the new limit;
- change descriptions, examples and defaults.

Anything else needs a new major: removing an input, list, field, action or action argument;
changing a type, an action's `kind` or a `set`'s `input`; adding a required input or argument, or making an optional
one required; and tightening a limit, since producers (an action's arguments are produced by
templates) already send values the old limit allowed. A publisher must
refuse a catalog that holds two versions of one major whose difference is not a minor change, or an
input, list or action whose `since` is above its contract's `minor`, or whose `since` is above 0
while it is `required`.

A template's `requiresMinor` is the highest `since` among the inputs, list fields and actions it
`reads` and `emits` (a list value takes the higher of its list's and its field's; theme roles count
as 0). The publisher computes it, so a template that reads a newer input never reaches an app that
doesn't fill it, and one that emits a newer action never reaches an app that can't route it.

Clients below level 3 don't filter by minor (§2.1). A publisher decides, per catalog and per
contract, whether that is acceptable: **lenient** (the default) leaves `minClient` as it is, and
**strict** raises it to 3 when any template of the contract has a `requiresMinor` above 0, so a
pre-minor client refuses the manifest and keeps the templates it had.

## 5. Selection

### 5.1 Eligibility

A template entry is eligible for a query (contract, player capabilities, optional width and height
in dp, optional design, variant, theme and tags, and optionally the contract `minor` the app
implements) when all of these hold: its contract equals the query's; its design exists, is not
revoked and has that contract; the entry is not revoked; with a `minor` in the query, its
`requiresMinor` is at most that minor; its
`bytes` fit the player's budget; its `rcApiLevel` is at most the player's; the player supports
every profile it lists; the surfaces are equal; the containers are equal; the size range covers
the requested width and height where given; for a `host` theme the player accepts `host`, and for
a baked theme it equals the requested theme, or, with none requested, is one the player accepts;
and every requested tag equals the entry's or its design's.

### 5.2 Choosing

Among eligible entries, the best is the one with the highest `rcApiLevel`, then the one whose
theme equals the requested theme, then the smallest size range (`(maxW − minW) × (maxH − minH)`,
with a missing minimum taken as 0 and a missing maximum as 100000), then the lowest `sha256`. A
query picks:

1. the best entry of the requested design, if any; else
2. among **automatic** designs, those whose `automatic` isn't false and that are `Always` or
   installed, the best entry of the requested variant, then of `default`, then of any.

A client must choose only among entries whose blob and assets it has on disk when it draws
without the network. A design that is requested but not available falls back to the automatic
choice, never to nothing.

### 5.3 Exact references

Decisions and feed items name a template by `designId` or by `sha256`. An exact `sha256`
reference must pass §5.1 and must be played exactly or not at all: a client must never substitute
another template for it, whether because it is missing, ineligible, revoked or not downloadable. A
decision must draw only in the template it names.

## 6. Resources

### 6.1 Image references

An image value (in a dataset, or a URL a document draws) has one of three forms, and any other is
invalid:

| Form | Meaning |
| --- | --- |
| `asset:<64 hex>` | a catalog image asset, verified like a blob |
| `https://…` | a remote image, fetched only as §6.2 allows |
| `app:<key>` | an image the app supplies itself (an avatar, an entity picture) |

`http:`, `data:`, `file:` and everything else is refused by binding. A document draws a data-bound
image from the URL `livery:value/<input>`, which the client resolves to that input's current value.

### 6.2 Remote images and the proxy

A document never fetches anything itself. A client fetches a remote image:

- through `imageProxy`, when the manifest has one: `urlTemplate` with `{url}` replaced by the
  source URL percent-encoded (RFC 3986, everything but `A–Z a–z 0–9 - . _ ~`), `{width}` and
  `{height}` by the slot's size in pixels clamped to 1…`maxWidthPx` / `maxHeightPx`, and
  `{format}` by the first of `formats`. When `sourceHosts` is not empty, the source host must equal
  one or be a subdomain of one, or the image is not fetched;
- otherwise, directly, only when its host equals or is a subdomain of a host the app allowlists.

The host is the URL's real authority host: the authority runs from after `https://` to the first
`/`, `?`, `#` or `\`; a URL whose authority holds user information (`a@b`, which reaches `b`) or
which uses a backslash there is refused outright; the port is dropped, the host is compared in
lowercase, and an IPv6 literal keeps its brackets.

Redirects are held to the same rule, before they are followed: a proxied fetch may only be
redirected within the proxy's own host, and a direct fetch only to an allowlisted host. A client
must never contact a refused destination, so it follows redirects itself and checks each hop;
checking the final URL is too late.

The proxy is the one image host the device trusts: it resizes for the slot, normalises the format
and strips cookies and tracking.

### 6.3 Asset entries and fonts

An asset entry is `sha256`, `bytes`, `mime`, `kind` (`image` or `font`), `name` (an image's name),
`font` (`family`, `weight` default 400, `italic` default false) and `license` (SPDX id, required for
fonts). A client must download a template's `assets` with it and must not hand a template out
until all of them are on disk. A template's `fonts` names families; each is a catalog font asset,
`google:<family>` (a downloadable font the app may opt into) or `system:<family>`. A player asks for
a face; the client answers with the exact face, else the nearest weight of the same style in the
family, else the player's fallback.

## 7. Actions

A template emits a Remote Compose **host named action** whose name is a contract action and whose
value is either nothing, a JSON object whose values are all strings (the arguments), a single text
value or number (passed as the argument `value`). Anything else is not an action. A client must
drop, and report, an action its contract doesn't declare, one whose kind has no handler, and an
`open-url` whose `url` is not allowlisted. Built-in kinds: `open-url`, `navigate`, `dismiss`,
`track`, `refresh`, `present` (opens the screen placement its `placement` names, §8.2), `set`
(changes the input its `input` names, §7.1); anything else is the app's (`app:<kind>`). A publisher
must refuse a `present` action without a `placement`, and a `placement` on any other kind.

An action's `args` declare its arguments, each a `string`, `int`, `long`, `float` or `bool` with
the input checks it carries (`required`, `maxLength`, `min`, `max`); a catalog declaring another type
is refused. A client checks them in name order before routing: a required argument missing, or a
declared one that isn't of its type or is out of its range, drops and reports the action. The
action's event carries only the declared arguments that were given (`args`); one the contract
doesn't declare still reaches the app's handler, but never an event, so an analytics pipeline only
ever sees what the contract names.

A `track` action needs no handler: the client records its `Action` event and does nothing else (a
handler an app registers for `track` still runs). It reports an interaction that changes only the
template's own state, combined with that change in one tap (`combinedAction(valueChange(…),
hostAction(…))`).

### 7.1 Writing an input back: `set`

A `set` action says the person changed one of the template's inputs, from the template's own
control (a toggle, a stepper, a slider), to the action's `value`. Its `input` names that input: one
of the contract's own `inputs` (not a list field), of a type an action can carry (`string`, `int`,
`long`, `float`, `bool`), and not a theme role. It declares no `args`: its one argument is `value`,
required and checked as that input is checked by binding (§4.1: type, `min`, `max`, `maxLength`). A
`bool` input also takes `1` and `0`, which is how a document's boolean reaches the host, and the
client passes it on as `true` or `false`. A publisher must refuse a `set` without an `input`, one
whose `input` breaks these rules or that declares `args`, and an `input` on any other kind.

A `set`'s text value is its `value` exactly as sent, never read as an arguments object: an empty
string, spaces, or text that starts with `{` are values a person can type. The document computes
`value` when tapped, so it needs no state of its own: a stepper's `+` sends
`quantity + 1`. The host writes the value back to wherever the input's value comes from (the app's
model, an A2UI data-model path) and the new value comes back into the document as the input, so
the document and its source can't disagree. A value the input doesn't allow (a step past `max`) is
dropped and reported like any action that fails its check, and nothing is written. Like any action,
a `set` goes to a handler for its kind; the client checks it first, its `Action` event carries
`value`, and an app with nothing to write back registers no `set` handler, which drops and reports
it (§7).

A host that dispatches only numeric host actions (a launcher widget's `RemoteViews` player) gets
the template's named actions rewritten when it is instantiated: each becomes the numeric action
whose id rc-players' `RcWidgetActionIds.register` gives the action's name, with the action's text
value, if any, as its metadata. The player evaluates that metadata when tapped and delivers it with
the tap, so the arguments arrive as above. Numeric values are dropped on such a host.

A host that runs only `SendPendingIntent` (a Glance Wear widget) gets each distinct tap (action and
constant arguments) rewritten to send its own `PendingIntent` index, in order of first appearance;
the host app supplies the `PendingIntent` for each. A `PendingIntent` is fixed when the widget is
published, so a tap whose value is not a constant text (or nothing) can't be carried there: it is
left unrewritten, which the host ignores.

## 8. Decisions, feeds and events

- A **decision** names a template (`designId` or `sha256`), carries the data, a TTL, tracking URLs
  and experiment arms.
- A **placement rule** is a local decision: schedule (`startEpochMs` inclusive, `endEpochMs`
  exclusive), conditions (platform, locale prefix, app version, segments), a sticky rollout, an
  experiment `arm` (§8.1) and a frequency cap. The highest priority eligible rule wins, ties to the
  lexically lower `id`. The rollout bucket is FNV-1a 32 of the UTF-8 bytes of
  `<experiment>:<bucketing id>`, unsigned, modulo 10000; the rule applies when the bucket is in
  `[fromBasisPoints, toBasisPoints)`. A rule with an `arm` applies only while that experiment runs
  for the user and assigns them that arm; its decision's `experiments` names it.
- A **frequency cap** counts the rule's impressions on the device. A client may share them between
  a user's devices, but decisions must read only what is on the device: a shared count arrives by
  a background sync, never while a slot decides.
- A **feed page** is `feed`, `items` (each an `id`, a template reference, data and tracking),
  `next` and `ttlSeconds`.
- **Events** are `Impression` (first frame drawn), `Viewable` (at least half on screen for one
  unbroken second), `Action` (with its declared `args`, §7), `Dismiss`, `Fallback`, `Error`,
  `Exposure` (§8.1) and `Leave` (the template left the screen, or its slot showed another item;
  `durationMs` is the time it was at least half on screen since it appeared), with the placement,
  decision, feed item, design and blob they concern, and `experiments`: the arm of every experiment
  that shaped what drew. A host that is only handed a document and never reports drawing it (a
  launcher or Wear widget) emits no `Impression`, `Viewable` or `Leave`: publishing is not drawing.
  Its taps are `Action` events like any other.

### 8.1 Experiments

An experiment is `id` (lowercase letters, digits and `_`, starting with a letter, at most 20, so it
names analytics parameters and remote configuration keys unchanged and two experiments never share
one), `arms` (each an `id`, a `weight`, default 1, and `designs`: contract
`id@major` → design id), a schedule (`startEpochMs` inclusive, `endEpochMs` exclusive),
`conditions` as for placement rules, and `allocationBasisPoints` (default 10000).

It **runs** for a user within its schedule when the conditions match. Local assignment enrols the
user when the bucket of `<id>:<bucketing id>` (as above) is below `allocationBasisPoints`, in the
first arm (arms with weight 0 skipped) whose cumulative weight is above `a × totalWeight / 10000`
(integer arithmetic), where `a` is the bucket of `<id>#arm:<bucketing id>`. The two buckets are
independent, so raising the allocation only adds users and an enrolled user keeps their arm. A
client may take the arm from another assigner instead (a remote configuration service); an arm id
the experiment doesn't list counts as no answer.

A slot of a contract that names no design takes its design arm from the experiments with an arm
that names a design for that contract: the first of them, in manifest order, that is running and
gives the user an arm. Experiments that name no design for the contract don't touch the slot. With
an arm that names a design, the slot draws that design, when it has a template for the slot and
automatic selection may pick it (the design is `Always` or installed, §5.2: an arm never draws a
skin nobody installed); otherwise it selects as usual and carries no arm. With an arm that names
none (a control), it selects as usual and carries the arm, so the control group is exposed the
same way as the others. The first
time a template an arm chose draws (its `Impression`, whether the arm picked its design or a rule
for the arm decided it), the client emits one `Exposure` event for that experiment and arm. Planning
is not exposure, and a host that reports no impressions reports exposure when it knows the user saw
the template. Exposure, not assignment, is what an analysis counts.

### 8.2 Secondary screens

A **screen placement** is one the app shows as a secondary screen, not in a slot. The app declares
each: its contract, the presentations it allows and its default. A presentation is `dialog`
(centred over a scrim, at the template's size), `sheet` (along the bottom, full width) or
`fullscreen`; `inline` is a slot's, never a screen's. A decision or placement rule for a screen
placement may ask for one (`presentation`, `inline` meaning none asked); the client uses the
placement's default when none is asked, and refuses, reporting a `Fallback`, one the app doesn't
allow, so a catalog or server can never show a full screen where the app allows only a sheet. A
screen's template is built for its presentation: its target's container is `screen:dialog`,
`screen:sheet` or `screen:fullscreen`, and a publisher must refuse such a template whose contract
declares no `dismiss` action, so every screen can be closed from itself.

A screen opens two ways, both on the device and never waiting on the network (it draws from what
is downloaded, or not at all, reporting a `Fallback`; a client may then download it for next time):

- **The app's moment** (an interstitial): the app asks whether a screen placement has something to
  show now. Its rules decide, with their schedule, conditions, arms and frequency cap; a rule's
  decision counts as an impression for its cap when it shows. Nothing shows while a screen is
  already showing.
- **A `present` action** (§7): its `placement` names the screen placement it opens. Its data comes,
  in order, from the opening decision's `screens` entry for that action (`template`, `data`,
  `presentation`), which becomes the screen's decision with `decisionId`
  `<opening decisionId>><action>` and the opener's tracking and experiments; from the app's own
  data for the placement and the action's declared arguments; or from the placement's rules.

Depth: a screen opened by the app or from a slot is at depth 1; a screen at depth 1 may open one at
depth 2; a screen at depth 2 can't open another (the `present` is dropped and reported as a
`Fallback`). Closing a screen closes the one it opened. Each close is a `Dismiss` with `detail`
`action` (the template's `dismiss`), `system` (back, a swipe, a scrim tap) or `replaced`; a
template's `dismiss` in a screen closes that screen, and in a slot is the app's. A screen is a
slot otherwise: `Impression`, `Viewable`, `Action` and `Leave` as §8.

The host writes `livery.inset.top`, `livery.inset.bottom`, `livery.inset.start` and
`livery.inset.end` (floats, dp) into a screen's template, the safe-area insets of the window it
draws in; a contract needn't declare them, and a template may read them.

## Conformance

`conformance/` holds inputs and the reference answers:

| File | Checks |
| --- | --- |
| `selection.json` | §5: a manifest and queries (with installed designs), and the `sha256` chosen or `null` |
| `binding.json` | §4.1: a contract and datasets, and the bindings or the sorted names of the problems |
| `resources.json` | §1 URL resolution, §6.1 image references, §6.2 proxied and direct URLs |
| `signing.json` | §3.2: manifests, signatures and trusted keys, and `accepted` or `rejected` |
| `experiments.json` | §8.1: experiments and users (bucketing id, time, segments), and the arm or `null` |
| `actions.json` | §7, §7.1: a contract's actions (`set`s among them) and emitted arguments, and the arguments its event carries or why it is dropped |
| `screens.json` | §8.2: screen placements, a requested presentation and depth, and the presentation used or why the screen is refused |
| `design-arms.json` | §8.1: experiments with design arms, and per contract and user the design arm (experiment, arm, design or none) or `null` |

The Kotlin client writes them (`./gradlew :livery-client:jvmTest -Plivery.conformance.update=true`)
and fails when its answers drift from them; every other implementation must run them in its own
tests. `signing.json` carries its private key so the cases can be regenerated. It is a test key and
signs nothing real.
