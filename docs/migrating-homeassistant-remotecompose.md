# Moving homeassistant-remotecompose onto Livery

The card design server in [homeassistant-remotecompose](https://github.com/yschimke/homeassistant-remotecompose)
(ha-rc) is the first and largest Livery user. This is the plan for replacing its generic parts
with Livery so that ha-rc keeps only what is about Home Assistant: converters, slot bindings,
`ha-cards` components, the Lovelace card schema and the Home Assistant connection.

## What maps to what

| ha-rc today | Lines | Livery | Stays in ha-rc |
| --- | --- | --- | --- |
| `ha-model/.../design/CardDesign.kt`: `DesignKey`, `DesignEntry`, `DesignManifest`, `GeneratedDesign` | ~230 | `Manifest`, `Design`, `TemplateEntry`, `Target` | `DesignKey` → a function from a card to a `TemplateQuery` plus tags (`cardType`, `shape`, `cardStyle`) |
| `ha-model/.../design/DesignQuery.kt` (`DesignQuery`, `DesignTags`) | 140 | `TemplateQuery`, `TemplateSelector`, design tags | — |
| `ha-model/.../design/DeviceMatch.kt` | 91 | a `device` tag matcher in selection (to add) | the device facts ha-rc reads from HA |
| `ha-model/.../design/DesignCatalog.kt` | 60 | `Design.previews`, contract `examples` | — |
| `ha-model/.../design/CardDesignSlots.kt`, `CardBindingRecipe.kt`, `HaSharedValues.kt` | — | — | **stays**: these compute the data a card contract's inputs take |
| `ha-client/.../design/DesignRepository.kt` (`DesignRepository`, `DesignPublisher`, `DesignServerConfig`) | 756 | `TemplateRepository`, `CatalogSource`, `livery-publish` | the choice of server URL in settings |
| `ha-client/.../design/CardDesignOverrides.kt` | 119 | a per-slot design choice store (to add beside `installed`) | the card id as slot key |
| `app/.../SkinStore.kt`, `Skins.kt` | 160 | `install` / `uninstall` / `installedIds`, `designs(contract)` | the style sheet UI |
| `ha-client/.../CardGenerator.kt` (`CardSource` chain) | 104 | resolve's fresh → stale → bundled fallback | the local converter as the last link |
| `rc-converter/.../LauncherDesigns.kt` (instantiate) | 373 | `livery-launcher-widgets` | the card-local action resolution |
| `wear/.../WearWidgetTemplate.kt` (container params, instantiate) | 232 | `livery-wear-widgets` | — |
| `design-server/.../LibraryChannels.kt`, `LibraryMirror.kt` | 656 | `livery-server` channels and mirrors | — |
| `design-server/.../AgentRelay.kt`, `AgentRelayMcp.kt`, `AgentSessionFile.kt`, `AgentRelayRoutes.kt` | ~1,300 | `livery-server` relay, `livery-mcp` | the per-kind draft gates (`AgentDesignSessions`, `DashboardAgentSessions`) as validators plugged into it |
| `ha-client/.../agent/AgentRelayClient.kt` | 411 | the relay client in `livery-client` | — |
| `embed/ha-card.js` (manifest, blob fetch and verify, player wiring) | — | `@yschimke/livery-web` | the HA OAuth, websocket and the binding port |
| `.github/scripts/publish-design-library.py` | — | `livery build` + `livery publish` | — |

The Home Assistant concepts become plain Livery data:

- each card type and binding schema is a **contract** (`ha-card/tile@1`), generated from
  `CardSchema`, with slot bindings (`light.s0.is_on`) and `card.*` values as inputs, `s<N>.tap` as
  `app:ha-action` actions, and catalog examples;
- `variant`, `cardStyle`, `author` and `shape` are design and template **tags**;
- `library:<cardType>` designs are `Always`; `user:<id>` designs are `OnRequest` (skins);
- the `HA:` shared values (`HA:sun.sun.elevation`) are inputs of a shared contract a template may
  also read, so the publisher's "reads only declared values" check covers them;
- channels (`main`, `pr-<n>`) are Livery channels with no change.

## Order

Each step is one ha-rc PR that keeps every surface working, behind the existing settings.

1. **Depend on `livery-protocol` and `livery-client`** from `ha-model` and `ha-client`. Add a
   converter from `DesignManifest` to `Manifest`, so the existing server keeps serving and the app
   reads it through Livery. Run `ClientCompatibilityTest` against both.
2. **Contracts from `CardSchema`.** Generate one contract per card type in `card-designs`; the
   library build emits a Livery catalog (`livery build` on the generated tree) beside today's.
3. **Switch the app's repository.** `DesignRepository` becomes a thin adapter over
   `TemplateRepository`; skins become installs; overrides become slot choices. Delete
   `DesignQuery`/`DesignTags`/`SkinStore` once nothing uses them.
4. **Widgets.** Move launcher instantiation and the Wear widget template to Livery's widget
   modules, keeping ha-rc's action resolution as the app's handler.
5. **Server.** Serve Livery channels from `design-server` (the same files), then replace
   `LibraryChannels`/`LibraryMirror` with `livery-server`'s.
6. **Agent relay.** Move the relay, its MCP surface and the client into Livery, with ha-rc's card
   and dashboard gates registered as validators.
7. **Web embed.** `embed/ha-card.js` uses `@yschimke/livery-web` for the catalog and keeps its HA parts.

What stays out: anything that knows about entities, Lovelace, HA auth or the converters.
