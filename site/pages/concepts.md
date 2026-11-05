title: Concepts
order: 2

# Concepts

## Contracts

A contract is the interface between a template and the app that plays it. Templates bind
`id@major`; a later minor may only add optional inputs and actions, so every template of a major
keeps working.

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
