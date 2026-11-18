# Kobento documents as Livery templates

[Kobento](https://mcp.kobento.dev/) (github.com/kobento, Apache-2.0, pre-1.0) is an independent
Remote Compose implementation: players (the C++ `kobento-libs` engine, `kobento-ts`, iOS, desktop,
and `kobento-mcu` on ESP32, STM32 and Raspberry Pi), a LAN push protocol (`kobento-net`), a JSON
compiler (`kobento-tools/json2rc`), and an MCP server that builds documents. This page is the plan
for letting its documents be templates in a catalog source tree, with Livery's checks, without
putting anything Kobento-specific into `livery-protocol` or `livery-client`.

**The answer: an optional module, no forks.** Everything a first slice needs is either in Livery
already (`TemplateImport`, `TemplateInspector`, the MCP and CLI plug-in points) or published
(`remotecompose-json` compiles the same AndroidX authoring JSON Kobento's `json2rc` does;
`rc-player-edit` knows what each player level reads). `livery-kobento` is to Kobento what
`livery-uibuilder` is to UI Builder. What Kobento would have to change is small, and is asked of it
upstream (below), not forked.

## What Kobento hands out

| Kobento output | How Livery takes it | Built |
| --- | --- | --- |
| a `.rc` (from `json2rc`, MCP `convert`, a device) | as it is | yes |
| AndroidX authoring JSON (Kobento's plain documents and demos) | compiled locally with `remotecompose-json` | yes |
| JSON calling Kobento components (`"componentSets": ["lib:…"]`) | refused, with "convert it with Kobento's MCP and import the `.rc`": the component library is Kobento's and not public | yes |
| a document with Kobento's 3D operations | refused: see [Targets](#targets-and-3d) | refused |

## In this slice (`livery-kobento`)

JVM only, shipped in the `livery` CLI's and `livery-mcp`'s distributions like `livery-uibuilder`,
not a published library (yet).

- `KobentoDocument`: tells the three inputs apart, compiles plain JSON, refuses component JSON.
- `KobentoScaffold`: a contract as the start of a Kobento document (below). It compiles and
  imports as it is, so a person, an agent or Kobento's `build_kit` starts from Livery's names.
- `KobentoTargetCheck`: what the target's player wouldn't play (below).
- `livery kobento scaffold | check | import` (`KobentoCommand`, a `LiveryCommand`). `import` is
  `livery import` with a compile and the target check in front, writing
  `source: {tool: kobento, file, format, …--source}` into the sidecar.
- `livery-mcp --kobento`: `kobento_scaffold` and `kobento_compile` (`KobentoExtension`). No Kobento
  account and no network: the agent runs Kobento's own MCP server side by side for `build_kit`,
  `convert`, `render` and `audit`, and passes Livery the JSON or the `.rc` it gets back. A
  passthrough to Kobento's MCP from inside `livery-mcp` adds a token to manage and buys nothing an
  agent with both servers doesn't have.

### The contract in a Kobento document

What Livery binds (PROTOCOL.md §4.2) in the authoring JSON dialect, which is what the scaffold
writes and what `TemplateInspector` reads back from the compiled bytes:

| Contract | Authoring JSON |
| --- | --- |
| an input `name` (string, float, int/long) | `{"type": "variable", "name": "USER:name", "vtype": "string" \| "float" \| "int", "value": …, "export": true}` in the root array's first pass |
| a bool | `"vtype": "int"`, 0 or 1 (a named boolean is an int) |
| a theme role | `{"name": "USER:theme.primary", "vtype": "color", "value": "#FF6750A4", "export": true}` |
| a list | `USER:<list>.count` and `USER:<list>.<i>.<field>` |
| an image | a bitmap whose URL is `livery:value/<input>` (not scaffolded yet) |
| an action | `{"onClick": {"type": "hostAction", "name": "<action>"}}`, `"value"` for arguments |

Kobento's own named values (`system.city`, `Control.knob`) are in no domain Livery binds: a
document that reads them imports only if the contract declares them, so in practice it is refused
(`aValueTheContractDoesntDeclareIsRefused`), which is the right answer for a template.

### Targets and 3D

Kobento's 3D extension is ten operations (opcodes 110–120: `DEFINE_MESH_3D` … `MESH_PRIMITIVE_3D`)
from an AndroidX change still in review (ag/4108133, registered in the experimental profiles).
No released AndroidX player draws them, and rc-players' decoder refuses them (`Unsupported operation
… opcode=114`), so Livery can't read such a document's names at all today.

- `KobentoTargetCheck` refuses a 3D operation unless the target names the **`3d` profile**. That
  needs no protocol change: selection already gives a template only to a player whose capabilities
  list every profile its target names (`Target.fits`), and no AndroidX player lists `3d`. It is
  also refused on `launcher-widget`, `wear-widget` and `notification`, whose player is Android's.
  While the decoder refuses 3D, the check reports "can't be a Livery template yet".
- Every other document is held to `rc-player-edit`'s `RcTargetProfile` for the target's level and
  profiles: an operation it doesn't read, or a header level above the target's. Not the whole
  `validateFor`, for the reason `livery-capture-cmp`'s `CaptureProfiles` gives (a template reads
  named values by design).

*Open:* `3d` is a profile name this module checks; once a player declares it, it belongs in
PROTOCOL.md's list of profile names (it changes no wire format).

## Planned

- **A Kobento device as a Livery surface (a bridge, an app of its own, not in Livery):** fetch and
  verify a signed catalog with `livery-client`, select for the device's caps (`surface` the app's
  own, its `rcApiLevel`, `profiles`, `size`), then either `livery-instantiate` the values into the
  bytes and `POST /send`, or send the template once and push data changes with `POST /named`.
  `/named` takes `name=value` lines, so a bridge sends `USER:name=Kitchen`; it avoids a full e-ink
  refresh per change. A small `livery-kobento-net` client (JVM, `/info`, `/send`, `/named`, mDNS
  optional) could live here; the selection and trust stay `livery-client`'s. Blocked on devices:
  the ESP32 player only shows documents built into its firmware today (receiving pushed documents is
  planned in `kobento-mcu`); `kobento-net`'s `reference_receiver.py` and the desktop player accept them.
- **Kobento's players as a `livery review` lane:** `livery-render` draws with rc-players;
  `kobento-ts` (already vendored in yschimke/compose-ai-tools as the `camaelon-js` lane) or the C++
  `rc2image` would add a second opinion for the targets Kobento plays. An extra lane runner behind
  a flag, under Node; nothing in Livery's own renderer changes.
- **Images in the scaffold**, and a `--look` option once looks map onto packs (below).
- `compile_rc_json` in `livery-mcp` itself, for any agent writing authoring JSON (`kobento_compile`
  covers it behind `--kobento` today).
- Publishing `livery-kobento` as a library, if a server wants the compile and check in-process.

## Kobento's looks and Livery's themes

Kobento's components come in four looks: light, dark, mono-dark (always-on) and mono-light (e-ink).

- **light and dark** are theme packs (`themes/*.json`, `mode: light | dark`), recolouring the
  `theme.<role>` values the template reads; one template serves both. A component's look is chosen
  at Kobento's compile time, so a component document should read its colours from `USER:theme.*`
  rather than bake one look, and that is an ask of Kobento (below).
- **mono-dark (always-on)** is a theme pack too (`mode: dark`, black surface), but it also wants
  less motion and less light; for a Wear ambient or always-on display it is a separate template of
  the same design, targeted at that surface, not only a recolour.
- **mono-light (e-ink)** is a target, not a theme: a different device class (no animation, slow
  refresh, one or few greys), so a separate template of the design for the e-ink device's surface
  and size, with a baked light theme (`theme: baked:light`), chosen by `TemplateSelector` like any
  other target.

## What to ask Kobento for (upstream issues, not forks)

1. **A published component-library manifest** (names, parameters, looks, the operations each
   compiles to) or the `convert` step as a library, so component JSON compiles in CI without an
   account; failing that, a stable `convert` output for a given input and library version.
2. **`/named` and Livery's names**: document that names may carry a domain (`USER:title`), how a
   value's type is chosen (colours, ints, floats), and an atomic batch, so one data change is one
   refresh.
3. **Receiving documents on the ESP32 player** (`POST /send`), already planned there.
4. **Component looks read from named colours** (`USER:theme.*`) rather than baked at compile time,
   so one document follows the host's theme.
5. **A capability descriptor in `/info`** (`rcApiLevel`, profiles incl. `3d`, size), which is what
   Livery's selection needs from a device.

## rc-players

Not changed from here. What would help Livery, as issues on yschimke/rc-players:

- **Decode the 3D operations** (110–120) in `rc-player-protocol`, even as opaque payloads, so
  `TemplateInspector` can read a 3D document's names and actions and the `3d` rule above can admit
  it for a `3d` target.
- **A named Kobento MCU profile** in `RcTargetProfiles` (as `WIDGETS_V6`): level 7, AndroidX and
  experimental operations, without the features its tests already find missing (variable padding;
  colour references are fine), so a target for a Kobento device is checked against what that player
  really plays rather than AndroidX's table.

## Open questions

- Should `livery-kobento` publish as a library, or stay a CLI/MCP extension like `livery-uibuilder`?
- Is `3d` the right profile name, and should it go into PROTOCOL.md now or when a player declares it?
- Is the device bridge an app Livery documents, or a module here (`livery-kobento-net`)?
- Should always-on be a theme pack, a separate template, or both?
