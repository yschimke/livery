# Livery in A2UI

*Proposal. Nothing here is built yet.* How a Livery template becomes one component of an
[A2UI](https://a2ui.org) surface, on Android, Compose Multiplatform, the web and iOS, without
Livery learning anything about A2UI and without A2UI learning anything about Remote Compose.

## The idea in one paragraph

An A2UI agent describes a screen as components from a **catalog** the client advertises; the client
draws them and reports events back. A Livery **contract** already is a component definition: typed
inputs with limits, lists, and named actions. So each contract an app serves becomes one A2UI
component, generated from the contract. The agent picks the component and supplies its inputs (as
literals or as bindings into the surface's data model) and wires its actions to A2UI events; the
client picks a signed, installed template for it exactly as a Livery slot does, binds the values
through `ContractBinder`, plays the document, and turns the document's host actions into A2UI
events. **The agent never sends Remote Compose bytes.** It chooses among reviewed designs and sends
data, which is what Livery already asks of every agent workflow ([DESIGN.md, AI
workflows](DESIGN.md#ai-workflows)).

```
agent ──updateComponents──▶ { "id": "promo", "component": "PromoBanner",
                              "headline": {"path": "/offer/title"}, "cta": "Start trial",
                              "design": "banner-classic",
                              "actions": { "cta": {"event": {"name": "startTrial"}} } }
                                   │
client: A2UI surface ─▶ PromoBanner (generated from promo/banner@1)
                          ├─ bind each property against the data model  (A2UI)
                          ├─ ContractBinder.bind(contract, dataset)      (Livery's one gate)
                          ├─ TemplateSelector: installed, signed, sized  (Livery's one gate)
                          ├─ play .rc, push values live                  (rc-players)
                          └─ host action "cta" ─▶ ActionGate ─▶ A2UI event startTrial
```

## The component is a slot, not a widget

The A2UI component never describes what the document draws. It is a **slot typed by a contract**:
the agent and the A2UI data model only ever see the contract's inputs, lists and actions, and the
document is delivered separately, through Livery, and may change at any time without the agent, the
A2UI message or the app changing.

- `live/status-tile@1` has a `level` input and a `toggle` action. One design draws `level` as a
  slider, another as a ring, next month's as two buttons; each reads the same named value and emits
  the same action. The agent binds `level` to `/lamp/brightness` once, and every design works.
- Writing back is by input too: a slider design and a stepper design both emit `set` for `level`
  (below), so the data model is updated the same way whichever control the design happens to use.
- What a document may read and emit is checked against the contract when it is published
  (`livery build --inspect`), so a new document can't need data or send actions the agent and the
  app don't already know about. A design that needs more is a new contract minor (optional
  additions) or a new major, never a surprise.

### How the document gets there

| Route | Who picks the document | When it changes |
| --- | --- | --- |
| **Catalog** (the default) | `TemplateSelector`, from the signed designs installed on the device, honouring the agent's `design` / `variant` preference and the slot's size | whenever the catalog publishes a new design or template; the next refresh, no app or agent change |
| **Decision** | the app's decision server: a `designId` or exact `sha256` from the same signed catalog (PROTOCOL.md §5.3), placed into the surface by the app | per decision |
| **Preview** (development only) | an exact `sha256` from an unsigned preview channel (`livery-mcp`'s `publish_preview`), in a client that allows unsigned catalogs | per draft |

In every route the bytes come from a Livery catalog channel the client already trusts, never inside
an A2UI message. That keeps A2UI messages small and textual, and keeps the A2UI agent out of the
trust path for executable layout.

## Why a component per contract, not one generic `RemoteCompose` component

A single `RemoteCompose { template, values: {…}, actions: {…} }` component is the obvious first
cut, and it is wrong for agents: its schema says "any object", so the agent learns nothing about
what a banner takes, and every mistake surfaces only at runtime. A component per contract puts the
contract's names, types, `maxLength`, `min`/`max`, `required` and `description`s into the catalog
schema the agent is prompted with, so the agent's own structured output is held to the contract
before the client ever sees it. The client still runs `ContractBinder` (it never trusts the
schema), but most errors never get that far.

The generic component remains useful as a **development** escape hatch, for previewing a draft
document from a preview channel by `sha256`, and only in a client that allows unsigned catalogs.

## Mapping

### Component name and catalog

| Livery | A2UI |
| --- | --- |
| contract `promo/banner@1` | component `PromoBanner`; a major above 1 adds a suffix: `promo/banner@2` is `PromoBannerV2` |
| contract `title` / `description` | component `description` |
| the contracts the app serves in-app | the components of a catalog the client advertises |
| contract minor | the optional properties the client advertises; the client advertises the minor it implements |

The component name is a deterministic projection of the contract id (path segments PascalCased and
joined), pinned by a conformance fixture so every platform generates the same schema. An app may
rename a component (a contract called `text` would collide with the basic catalog's `Text`).

A client's catalog is **the basic catalog plus the Livery components**: the AndroidX API already
composes this way (`A2uiCatalog(id, basic.components + livery, basic.functions)`), and the web
renderers register custom components beside the basic ones. Its id is the app's own URI with a
version, e.g. `https://example.com/a2ui/catalog/livery-v1`. A client advertises a component only
for a contract it has at least one usable in-app template for, so the catalog negotiation tells the
agent what this device can actually draw.

### Properties

Every contract input becomes a property; the agent may give a literal, a data-model binding
(`{"path": "/offer/title"}`) or a function call, as for any A2UI dynamic property.

| Contract | A2UI property schema |
| --- | --- |
| `string` (`maxLength`) | `DynamicString` (`maxLength` in the description and, for literals, the schema) |
| `int`, `long`, `float` (`min`, `max`) | `DynamicNumber` |
| `bool` | `DynamicBoolean` |
| `color` | `DynamicString`, pattern `#RRGGBB` / `#AARRGGBB` |
| `image` | `DynamicString`: `asset:<sha256>`, `https://…` or `app:<key>` (PROTOCOL.md §6.1) |
| list `tags` (`item`, `max`) | a dynamic value bound to an array of objects (`{"path": "/tags"}` or a literal array) |
| `required` | `required` |
| `default`, `description`, `since` | the property's description; `since` above the client's minor: not advertised |
| theme roles (`role`) | not advertised: filled from the surface theme (below) |

Plus three static properties every Livery component has:

| Property | Meaning |
| --- | --- |
| `design` | a design id to prefer; missing or unavailable falls back to automatic selection (PROTOCOL.md §5.2) |
| `variant` | a variant to prefer (`compact`, `hero`) |
| `actions` | an object, one A2UI `Action` per contract action name: `{"cta": {"event": {...}}}` |

Actions are grouped under `actions` because a contract may name an input and an action alike
(`promo/banner` has both a `cta` input and a `cta` action).

### Values: binding, and the one gate

On every recomposition the component binds each property against the surface's data model (A2UI's
`bind`), assembles the results into one dataset JSON object, and hands it to
`ContractBinder.bind(contract, dataset)`. The resulting `Bindings` are pushed into the playing
document as named values, exactly as `LiveryTemplate` does today, so a data-model update animates
in place and the first frame already shows current data.

- A property still pending (A2UI's progressive rendering) keeps the component in A2UI's loading
  state (`isReady` is false until every `required` input has resolved).
- A dataset that doesn't bind is **reported to the agent** with A2UI's `reportError`, naming the
  problems `ContractBinder` gives, so the agent can correct itself; the component draws its
  fallback (below).
- An `image` value that is `https://…` is fetched only through the catalog's image proxy or the
  app's allowlist (PROTOCOL.md §6.2), exactly as in a slot. A2UI's own `Image` component is the
  host's concern and is not affected.

### Theme

A theme role input (`"role": "primary"`) is filled, in order, from the agent's literal or binding,
from the surface theme the agent sent in `createSurface` (`primaryColor` → `theme.primary`), and
from the host's Material colour scheme. So a Livery component matches the A2UI components around
it without the agent saying anything.

### Choosing and drawing the template

The component is a Livery slot whose decision comes from the agent:

1. Query: the contract, `in-app` surface, the space A2UI's layout gives the component (width and
   height in dp), the `design` and `variant` properties, the host theme, the minor the app
   implements.
2. `TemplateSelector` picks among installed, verified templates, from disk only. Rendering never
   waits on the network.
3. A requested design that isn't available falls back to the automatic choice, never to nothing.
   If the contract has no playable template at all (it was advertised, then revoked), the
   component reports an A2UI error and draws the app's native fallback for that contract, or an
   empty box of the requested size.

An exact `sha256` is deliberately not a production property: an agent naming bytes is what a
decision server does, and a decision server is a separate trust domain. A Livery decision can still
be carried *into* an A2UI surface by the app (below).

### Actions, events and analytics

When the document emits a host action, the component first passes it through `ActionGate` (it must
be declared by the contract, with its declared arguments of their types), then:

| `actions.<name>` from the agent | What happens |
| --- | --- |
| `{"event": {"name", "context"}}` | an A2UI event to the agent: `name`, the agent's `context` (resolved by A2UI) **plus the action's declared arguments** under their own names. A context key that shadows a declared argument is refused when the component is built |
| `{"functionCall": …}` | run by the A2UI client (e.g. `openUrl`, which then obeys the A2UI host's own rules) |
| not given | the app's Livery `ActionRouter`, as in any slot: `navigate`, `open-url` (allowlisted), `dismiss`, `track`, `app:*` |

So the agent wires only what it cares about, and a design's `dismiss` or `track` works with no
agent wiring at all. Every action, wired or not, is an `Action` event on the app's `EventSink`, with
`Impression`, `Viewable` and `Leave` as for any template; the event's `decisionId` is
`a2ui:<surfaceId>/<componentId>`, so analytics can tell agent-placed templates from the app's own.

### Writing back: two-way binding

A2UI lets an input component write the data model at a property's path (`bindUpdater`). A template
that changes one of its own inputs (a toggle, a stepper) needs the same, and so does an app that
plays a template live outside A2UI. This is a general Livery feature, so it is proposed as protocol,
not as an A2UI special case:

- **Protocol (new level):** a built-in action kind `set`, with `input` naming the contract input it
  updates and one argument `value` of that input's type: "the user changed this value". A publisher
  refuses a `set` whose input doesn't exist or isn't a scalar. A client routes it like any action;
  `ActionGate` checks `value` against the input's spec (type, `min`/`max`, `maxLength`).
- **In A2UI:** the component routes `set` to the updater of the property bound to that input. When
  the agent gave a literal, there is no updater: the action is dropped and reported, as an
  unhandled kind is. The write changes the data model, the property re-binds, and the new value is
  pushed back into the document, so the document and the model can't disagree.
- **Outside A2UI:** an app's `set` handler updates its own model and the live push does the rest.

Until that level ships, the A2UI component works one way (data in, events out), which already
covers banners, cards and feeds.

### What an app can do that an agent can't

The app owns the surface. `LiveryA2ui.decision(placement)` can pre-populate a Livery component in a
surface from a Livery decision (a promotion inside an agent's answer, for instance): the template
is the decision's, `sha256`-exact, and the agent sees it as a component it may move but not
re-skin. This keeps PROTOCOL.md §5.3: a decision draws only in the template it names.

## Libraries

All optional modules: nothing in `livery-protocol` or `livery-client` changes except the `set`
action kind.

| Module | Platforms | Does |
| --- | --- | --- |
| `livery-a2ui` | KMP (JVM, Android, iOS, Wasm), no Compose | **Produces.** The projection: contract → A2UI component definition (JSON Schema), a whole Livery catalog → an A2UI catalog JSON; the dataset assembly from resolved properties; event mapping. Server-side builders for agents written in Kotlin (`liveryComponent("promo", promoBanner) { headline(path("/offer/title")); action("cta", event("startTrial")) }`) and a validator that runs `ContractBinder` over the literal parts of an `updateComponents` message before it is sent. The same code the clients run |
| `livery-a2ui-compose` | Android (AndroidX `androidx.a2ui`), desktop and Wasm (`ee.schimke.a2uicmp` port) | **Renders.** `LiveryA2uiComponents(host, contracts)`: one `A2uiComponent` per contract over `LiveryTemplate`; `liveryA2uiCatalog(basic, host)` to combine with the Material basic catalog |
| `livery publish` | CLI | writes `a2ui-catalog.json` beside `manifest.json`, so an agent in any language (A2UI's agent SDKs are Python and TypeScript) is prompted with the same schema the clients advertise |
| `livery-mcp` | CLI | `a2ui_component_schema(contract)` and `validate_a2ui(message)`, for an agent authoring an A2UI response against a catalog |
| `@livery/a2ui` | browsers | **Renders on the web.** A custom component for A2UI's web renderers wrapping `<livery-template>` from `@livery/web`; needs the TypeScript port (roadmap 4) |
| Swift `LiveryA2UI` | iOS, macOS | the same component over `LiveryTemplateView`, for A2UI's SwiftUI renderer once it is published |

### One source, two A2UI artifacts on Compose

`androidx.a2ui:*` is Android-only, and the Compose Multiplatform port (`ee.schimke.a2uicmp:*`, from
[yschimke/a2ui-catalog](https://github.com/yschimke/a2ui-catalog)) keeps upstream's packages and
covers JVM and Wasm. So `livery-a2ui-compose` compiles one source directory twice: in `androidMain`
against the AndroidX AARs (an Android app keeps the real library) and in `skikoMain` against the
port. Neither is a common dependency, so the directory is added to both source sets rather than
being `commonMain`. iOS waits for the port to add Apple targets (its seam needs `Locale`,
`DateFormat` and `NumberFormat` there); the Swift path above is the alternative.

The A2UI release train is alpha and moves in lockstep; `livery-a2ui-compose` pins one release and
moves with it, as a2ui-catalog does.

### The web

Two routes, in order:

1. **Now, with nothing new:** a Compose/Wasm page hosting the whole A2UI surface (the port's
   `material3-a2ui` plus `livery-a2ui-compose`), as a2ui-catalog's own `a2ui-desktop` does on the
   desktop. Correct but heavy, and it doesn't drop into a Lit, Angular or React app.
2. **Native:** the TypeScript client (`@livery/web`, roadmap 4) with rc-players' TypeScript player
   behind a `<livery-template>` element, and `@livery/a2ui` registering one custom component per
   contract with the web renderer. Its binding and selection are held to Kotlin's by the existing
   conformance fixtures, plus the A2UI one below.

A2UI's web renderers share `@a2ui/web_core` (`MessageProcessor`, `Catalog`, `DataContext`), and
`basicCatalog.derive({catalogId, components, functions})` builds "basic plus ours" directly. A
custom component there is an API object (a name and a Zod schema) plus an implementation per
renderer: `createComponentImplementation(Api, …)` in `@a2ui/react` (which injects a setter such as
`setUsername` for a two-way property), a subclass of `A2uiLitElement` in `@a2ui/lit`, a
`CatalogComponent` in `@a2ui/angular`. So `@livery/a2ui` is one core that generates the API objects
from contracts (the same projection as `livery-a2ui`, checked by the same fixture) and three thin
wrappers around `<livery-template>`, one per renderer.

On the wire, a client action is `{name, surfaceId, sourceComponentId, timestamp, context}`, so a
Livery action arrives at the agent with `sourceComponentId` naming the Livery component and the
action's declared arguments inside `context`.

### Apple and Flutter

A2UI's own SwiftUI renderer (`A2UISwiftUI`) and Flutter renderer (`a2ui_flutter`) exist in the A2UI
repository but are not published yet. Once the SwiftUI one is, `LiveryA2UI` is the same projection
in Swift over `LiveryTemplateView` and rc-players' native player. Flutter has no Remote Compose
player, so it is out of scope.

## Conformance

A new fixture, `conformance/a2ui.json`: contracts and the A2UI component definitions they project
to (names, schemas), resolved property sets and the dataset or problems they give, and emitted
actions with an agent's `actions` and the A2UI event (or Livery route, or refusal) each produces.
Kotlin writes it; the TypeScript and Swift adapters must reproduce it.

## Order of work

1. `livery-a2ui`: projection, dataset assembly, event mapping, `conformance/a2ui.json`;
   `livery publish` writes `a2ui-catalog.json`.
2. `livery-a2ui-compose` on Android against the AndroidX AARs, with a sample: the sample catalog's
   `promo/banner` inside an A2UI surface, rendered in CI by `livery-render`.
3. The same source on desktop and Wasm against the port.
4. The `set` action kind (protocol level, Kotlin, Swift, fixtures), and two-way binding in A2UI.
5. `@livery/a2ui`, after the TypeScript port.

## Open questions

- **Catalog identity.** A2UI's `catalogId` is an identifier, not a URL anyone fetches, and the
  set of components depends on what the device has installed, so two devices may advertise the
  same id with different components. Either the id carries a digest of the contract set, or
  (simpler) the client sends the catalog in `a2uiClientCapabilities.inlineCatalogs` when it differs
  from the published `a2ui-catalog.json`.
- **Lists of components versus lists in a template.** A feed can be an A2UI `List` of Livery
  components (one template per item, as Livery feeds do) or one template with a list input. The
  first is better for viewability per item; worth a sample of each.
- **Size.** A2UI layouts measure children; a Remote Compose document lays itself out within the
  constraints it is given. A component with no height constraint needs a rule (the template's
  intrinsic height, or the smallest `minHeightDp` of its size range).
