title: Concepts
order: 2

# Concepts

## Contracts

A contract is the interface between a template and the app that plays it. Templates bind
`id@major`; a later minor may only add optional inputs and actions, so every template of a major
keeps working (see [Versions within a major](#versions-within-a-major)).

```json
{
  "id": "promo/banner", "major": 1, "dataSource": "Decision",
  "inputs": {
    "headline": { "type": "string", "required": true, "maxLength": 40 },
    "hero":     { "type": "image" },
    "accent":   { "type": "color", "default": "#FF6750A4" }
  },
  "lists": { "tags": { "item": { "label": { "type": "string" } }, "max": 3 } },
  "actions": { "cta": { "kind": "navigate", "args": { "route": { "type": "string" } } } },
  "examples": { "default": { "headline": "Try Premium free" } }
}
```

- Types are `string`, `int`, `long`, `float`, `bool`, `color` (`#RRGGBB`, `#AARRGGBB`) and `image`.
- Lists flatten to `tags.count`, `tags.0.label`, … because Remote Compose named values are flat.
- Data that doesn't fit is **refused, not dropped**: an unknown key, a wrong type, an over-long
  string. The same check runs in the app, the publisher, decision servers and agent tools.

### Versions within a major

Apps already installed keep the contract minor they were built with, so a new minor may only add
what an old app can ignore, and each addition says when it arrived with `since`:

```json
{ "id": "promo/banner", "major": 1, "minor": 2,
  "inputs":  { "subtitle": { "type": "string", "since": 2 } },
  "actions": { "share": { "kind": "app:share", "since": 2 } } }
```

| Change | Within the major (new minor) | Needs a new major |
| --- | --- | --- |
| Add an optional input, list, list field or action, with `since` | ✓ | |
| Loosen a limit (`maxLength`, `min`/`max`, a list's `max`) | ✓ | |
| Change a description, an example or a default | ✓ | |
| Add a required input, or make one required | | ✓ |
| Remove an input or action; change a type, an action's `kind` or `args` | | ✓ |
| Tighten a limit | | ✓ |

The publisher refuses a catalog that breaks these rules. It also gives every template a
`requiresMinor`: the newest `since` among what it reads and emits. An app says which minor it
implements, and is never offered a template that needs a newer one; an older version of the same
design plays instead:

```kotlin
TemplateQuery("promo/banner@1", caps, minor = 1)   // or slot.query("promo/banner@1", minor = 1)
```

Leave `minor` out to accept any. Before a change ships, `livery check` compares the catalog with
the version already published (see [Hosting](hosting.md#checking-a-change)).

Apps on a Livery client from before minors (protocol level 2) don't filter this way: they may get
a template whose new input they don't fill (it draws its own default) or whose new action they
can't route (the button does nothing). Decide per catalog, or per contract, in `catalog.json`:

```json
"minorPolicy": { "default": "lenient", "contracts": { "promo/banner@1": "strict" } }
```

**Lenient** (the default) lets those clients keep updating. **Strict** raises the manifest's
`minClient` to 3 as soon as a template of that contract needs a newer minor, so those clients
refuse the update and keep the templates they had.

## Designs, templates and targets

A **design** is a look for one contract (`banner-classic`, `banner-bold`). It has one or more
**templates**, each a values-free `.rc` for one **target**:

| Target field | Meaning |
| --- | --- |
| `surface` | `in-app`, `launcher-widget`, `wear-widget`, `wear-app`, `web`, `desktop`, `tv`, `snapshot` |
| `rcApiLevel`, `profiles` | the document's Remote Compose level and operation profiles |
| `size` | the dp range it lays out for (it carries its own size tiers) |
| `theme` | `host` (colours pushed as `theme.*`) or `baked:light` / `baked:dark` |
| `container` | a fixed host container, e.g. `wear:small` |

The client picks, from what's on disk, the chosen design's richest template the player can draw
with the tightest size fit; else the preferred variant's, else `default`'s.

A **theme pack** (`themes/<id>.json`) is named values, usually `theme.*` colours, that a slot pushes
beside its data: `repository.theme("dusk")` gives them as bindings to pass as `extra`. Changing a
pack recolours every template that reads those names, with no new document. Theme packs and
placement rules come from `repository.current()`: the downloaded catalog, or the bundled one before
the first download.

## Install policies

| Policy | Downloaded | Used for |
| --- | --- | --- |
| `Always` | every refresh | the built-in look, hot placements, feed templates |
| `OnRequest` | when installed | skins and themes people pick |
| `OnDemand` | when first needed | ad creatives, rare feed templates |

A design nobody installed is never drawn by surprise. A **preview** downloads one template without
installing it, so a picker can show it live.

## Data reaches a template three ways

1. **Live push**: in-app players get values before the first frame, then each change.
2. **Instantiate**: launcher and Wear widgets can't receive pushes, so the values are written into
   the document (`livery-instantiate`).
3. **Server instantiate**: the same, on a server, for email or notification snapshots.

## Catalogs, channels and trust

A catalog channel (`main`, `staging`, `pr-123`) is one signed `manifest.json` plus immutable,
content-addressed files. Apps verify the Ed25519 signature with keys they embed, refuse a lower
`serial` than one they accepted, and stop using a catalog a grace period after it expires. See
[Signing](signing.md) and the [protocol](protocol.md).
