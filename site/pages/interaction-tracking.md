title: Interaction tracking
order: 23

# Interaction tracking

**In one line:** a template reports what the user did through the actions its contract declares,
so every tap is named the same whichever design drew it, and carries only the arguments the
contract allows. Livery reports when a template is seen and for how long, and hands all of it to
your analytics, Datadog or Sentry.

## Why not autocapture

Tap-tracking SDKs (Sentry, Datadog, Amplitude's autocapture) find the tapped node in the app's
own Compose layout tree and name it by a tag. A Livery template is one player node: its buttons
are inside a Remote Compose document, not in that tree. So those tools see a tap on the slot, at
best, and a design swapped from the server would change every name they track anyway.

Livery tracks interactions by **contract action** instead. `expand` is `expand` in the built-in
design and in the experiment's retro design, so its tap-through compares across A/B arms
(see [Analytics and experiments](analytics-and-experiments.md)).

## Actions are the interactions

Every action a template emits goes through one check (`ActionGate`, PROTOCOL.md §7) before it is
routed:

- an action the contract doesn't declare, a required argument missing, or a declared one of the
  wrong type or out of range is dropped and reported as an `Error`;
- the `Action` event carries **only the declared arguments** (`args`, `arg_<name>` in analytics);
  an argument the contract doesn't declare still reaches the app's handler, but never an event,
  so free text can't leak into analytics by accident.

```json
"actions": {
  "open":   { "kind": "navigate", "args": { "route": { "type": "string", "required": true, "maxLength": 40 } } },
  "expand": { "kind": "track", "since": 1,
              "args": { "section": { "type": "string", "maxLength": 20 },
                        "index": { "type": "int", "min": 0, "max": 9 } } }
}
```

Argument types are `string`, `int`, `long`, `float` and `bool`; `livery build` refuses others.

## Tracking what only changes the template

A `track` action needs no handler: the client records it and does nothing else. Use it for an
interaction that only changes the template's own state, an expanded card, a tab, a carousel page,
by sending it in the same tap as the change:

```kotlin
val v = LiveryValues(contract)
val expanded = remember { MutableRemoteBoolean(false) }
RemoteColumn(
  RemoteModifier.clickable(combinedAction(valueChangeAction(expanded, !expanded), v.action("expand", mapOf("section" to "specs")))),
) { … }
```

That is `livery-capture-cmp`, on rc-players' creation API. On AndroidX's alpha (`livery-capture`)
the state is `rememberMutableRemoteBoolean(false)` and the change `valueChange(expanded, !expanded)`.

`LiveryValues.action` (in both, see [Authoring templates](authoring.md)) refuses an
argument the action doesn't declare, or one that doesn't fit, when you capture. For a list,
pass the item's position: `v.action("open_item", mapOf("index" to "$i"))`.

## Seen, and for how long

| Event | When |
|---|---|
| `Impression` | the first frame drew |
| `Viewable` | at least half on screen for an unbroken second (Compose's `onVisibilityChanged`) |
| `Leave` | it left the screen, or the slot showed another item; `durationMs` is the time it was at least half on screen |

A feed is one template per item, so each item has its own `Impression`, `Viewable` and `Leave`.
Visibility *inside* one document (which row of a list in a single template scrolled into view)
isn't reported: the player doesn't say. See Planned.

## Integrations

| Module | What it does |
|---|---|
| `livery-firebase` | `FirebaseAnalyticsSink`: events in Firebase Analytics, exposures as user properties |
| `livery-amplitude` | `AmplitudeAnalyticsSink`: events in Amplitude, exposures as user properties |
| `livery-datadog` | `DatadogRumEventSink`: a tap is a RUM `TAP` action `livery <action>`, `Error` and `Fallback` are RUM errors, the rest `CUSTOM` actions, all with `livery.*` attributes |
| `livery-sentry` | `SentryBreadcrumbEventSink`: events as breadcrumbs (a tap is a `ui.click` user crumb), so an issue shows the templates seen and tapped before it; `SentryCompose.slotModifier` puts Sentry's tag on each slot |
| any other | an `AnalyticsSink` (flat events) or an `EventSink` (Livery's own events) |

Each adds its SDK as `compileOnly`: the app brings and starts its own. Combine them:

```kotlin
val events = eventSinks(
  AnalyticsEventSink(FirebaseAnalyticsSink(Firebase.analytics)),
  DatadogRumEventSink(),
  SentryBreadcrumbEventSink(),
)
```

### Tagging the slot

So that a tool which names a tapped node by its tag attributes the tap to the right slot, each
template's node carries `plan.slotTag` (`livery:<placement, or the contract>:<design>`): a
`testTag` in Compose by default, the accessibility identifier in SwiftUI. Supply your own with
`LiveryHost(…, slotModifier = SentryCompose.slotModifier)`.

## Widgets and notifications

A launcher widget, a Wear widget or a notification never reports drawing: the system shows it,
and whether anyone looked is unknown. So they have no `Impression`, `Viewable` or `Leave`, and
their experiment exposure is up to the host (`Experiments.exposed`, when it knows the user saw
it). Their taps arrive through the app's `PendingIntent` or receiver and go through the same
`ActionRouter`, so `Action` events, with their declared arguments, are the same as in the app.

## Planned

- **Visibility inside a document.** A player feature, in rc-players, to tell the host when a
  tagged component of a document comes into view, for lists inside one template. Until then, use
  one template per item.
- **Swift integrations.** The Swift package checks actions, records `track` actions, carries
  declared arguments and reports `Leave`; analytics sinks for Swift are still to come.
