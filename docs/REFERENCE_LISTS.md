# Reference lists and host vocabularies

Status: **proposal**, nothing built yet. Targets protocol level 9.

The motivating user is homeassistant-remotecompose's
[migration plan](https://github.com/yschimke/homeassistant-remotecompose/blob/main/docs/architecture/livery-migration.md)
(its L2 and L2b). Everything here is generic: an app's concepts stay data, as AGENTS.md requires.

## The problem

Today a list in a contract carries **values**: a feed item binds `items.0.title`,
`items.0.price`, and so on. That fits data the app computes per slot. It fits badly when a slot
shows an **object the app already tracks**, where each object has many fields, changes on its
own, and appears in many slots at once:

- a product whose price and stock appear in several feed cards and a widget;
- a device whose state appears on a dashboard card, a launcher widget and a watch tile;
- a person whose presence appears in several cards.

With value lists, every slot copies every field of every object it shows, and the app recomputes
and re-pushes each copy on every change.

Apps then invent their own naming to keep templates reusable. homeassistant-remotecompose captures
against fake ids (`light.s0.is_on` is "slot 0, a light, is on") and copies every field into every
card. The contract can't describe those names without hundreds of expanded inputs.

## The proposal

### 1. Host vocabularies

A **vocabulary** is a `Contract` with two new fields:
- `domain`: a Remote Compose named-value domain other than `USER`;
- `dataSource: Host`: the host fills it, not the slot's data.

Contracts are reused so that binding, evolution (`since`, minors) and conformance apply unchanged.

```json
{ "id": "shop/product", "major": 1, "minor": 0, "domain": "SHOP", "dataSource": "Host",
  "keyed": { "id": { "type": "string", "maxLength": 64, "pattern": "^[a-z0-9_.-]+$" },
             "fixed": [] },
  "inputs": { "price": { "type": "string" }, "in_stock": { "type": "bool" } } }
```

- **Plain** vocabularies (no `keyed`) have fixed names: `livery/device@1` with
  `host.ambient`, battery and network.
- **Keyed** vocabularies name each value `<DOMAIN>:<id>.<field>` (`SHOP:sku-42.price`).
  - A template may read a keyed value only through a reference list (below), or by one of the
    vocabulary's `fixed` ids: objects every host has under the same id. For Home Assistant that is
    `sun.sun`.
  - A publisher must refuse any other read in the domain. A design therefore never names a real
    object, and only the host decides which objects a slot shows.

### 2. Reference lists

`ListSpec` gains `ref`, naming a keyed vocabulary:

```json
"lists": { "products": { "ref": "shop/product@1", "max": 4,
                          "item": { "badge": { "type": "string" } } } }
```

- **Data.** A slot's dataset gives each item as `{ "id": "sku-42", "badge": "New" }`.
  `ContractBinder` checks the id against the vocabulary's `keyed.id`, and the item's own fields as
  today.
  - The bound result gains a **reference table**: `products.0 → sku-42`.
  - It still binds `products.count`.
- **Names in the document.** A template reads `products.0.price` (vocabulary field) and
  `products.0.badge` (item field), both in `USER`, exactly as a value list's names. Templates and
  authoring tools see no difference. The capture API (`LiveryValues`) gets typed handles for the
  vocabulary's fields.
- **Values.** The host keeps one **value store** per vocabulary: `HostValues`, keyed by
  `<id>.<field>`. The app writes each object once, when it changes. Each played slot reads, through
  its reference table, only the names its template `reads`.

### 3. Playing

- **Live players** (`LiveryTemplate`). The card keeps its one prepared document per template, as
  `LiveryResources` requires.
  - Its named-value map is fed from the store through the reference table. On a store change, only
    the slots referencing that id, and only the names they read, are written.
  - That is a copy per slot, but only of the names a template reads, and only on change. Per-object
    fields are computed once, by the app.
  - The player needs no aliasing support. Values are seeded before the first frame, like any
    binding.
- **Instantiate** (`livery-instantiate`: launcher, Wear widgets, notifications, server). These
  already make one document per instance, so they resolve references while baking:
  - the values are written in, as today;
  - or, for a host that pushes per object (a wall panel's long-poll), the named variables are
    renamed to `<DOMAIN>:<id>.<field>`.

  The rename is a new rc-player-edit pass beside `hardcodeNamedValues`.
- **One host per document.** References resolve in the host that plays the document. An app with
  several backends (two shops, two homes) keeps one store per backend, and no document names a
  backend.

### 4. Selection and discovery

- `TemplateEntry.uses`: the vocabularies a template reads, through reference lists or fixed ids,
  computed by the publisher from the document's reads.
- `PlayerCapabilities.provides`: the vocabularies the host fills. A template with
  `uses ⊄ provides` is ineligible (§5.1), like a missing feature, so the next design is chosen.
- `TemplateInspector.conformance(facts, contracts, vocabularies)`: which contracts a document
  implements, and what it reads beyond them, by vocabulary. For example: implements
  `shop/card@1`, uses `livery/device@1` (`host.ambient`). It feeds `livery import`, `livery build`
  (writing `uses` and `implements` into the sidecar, so authors never declare them) and
  `livery-mcp`'s `validate_template`.

### 5. Compatibility

- **Level 9.** An older client ignores `ref`, `uses` and `domain`.
  - It would bind a reference list's items as values, which `ContractBinder` refuses (unknown field
    `id`) unless the app sends values.
  - It would also play a template whose vocabulary reads it never fills.
  - A field an old client doesn't know can't protect it: level-8 `TemplateSelector.eligible`
    checks neither `requiresLevel` nor vocabularies. The only gate shipped clients honour is the
    manifest's `minClient` (`TemplateRepository.refresh` refuses a manifest above its level).
  - So a catalog with any entry that uses these semantics **must set `minClient >= 9`**, and
    `livery build` raises it automatically. Old clients then keep the last manifest they accepted.
  - An app that must keep serving old clients publishes those entries on a separate channel or
    catalog, alongside a level-8 one.
  - `requiresLevel` is still added per entry, for later levels: from level 9, a client checks it
    in eligibility, so a level-10 feature can be excluded per entry instead of per catalog.
- **Existing names.** An app migrating from its own naming can give a reference list a
  `names` mapping for its old flattened names (`"{type}.s{i}.{field}"`), used only to read
  templates captured before the change. New captures use the default names.

## Work

| Part | Module |
| --- | --- |
| `Contract.domain`, `dataSource: Host`, `keyed`, `ListSpec.ref`, `TemplateEntry.uses` / `requiresLevel`, `PlayerCapabilities.provides` | `livery-protocol`, PROTOCOL.md §4 and §5 |
| Binding ids and reference tables; `HostValues` | `livery-client` (`ContractBinder`, `SlotPlanner`) |
| Store-fed named values | `livery-compose` (`LiveryTemplate`, `LiveryHost.hostValues`) |
| Resolve or rename on instantiate | `livery-instantiate`, with an rc-player-edit rename pass |
| `uses` inference, the domain check, `conformance` | `livery-catalog`, `livery-instantiate` (`TemplateInspector`) |
| Typed vocabulary handles | `livery-capture-core`, `livery-capture-cmp`, `livery-capture` |
| Fixtures: bind with refs, selection with `uses`/`provides`/`requiresLevel`, refused domain reads | `conformance/`, the Swift port |
| Site pages for contracts and lists, marked *Planned* until shipped | `site/pages/` |

## Open questions

- Do the CMP and AndroidX View players accept host-pushed values in a non-`USER` domain, for
  fixed-id reads (`SHOP:` ids, Home Assistant's `HA:sun.sun.elevation`)?
  homeassistant-remotecompose's `HA:` shared values say the CMP player does. Confirm both, plus the
  native iOS player.
- Should `fixed` ids be declared per vocabulary, or per catalog (a catalog that knows its
  backend)? This proposal says per vocabulary.
- Image fields in a vocabulary (a product photo) follow §6 as image inputs do. Is a per-object image
  cache in `ImageResolver` worth adding alongside?
