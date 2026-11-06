title: Interstitials, dialogs and full screens
order: 12

# Interstitials, dialogs and full screens

**In one line:** show a template as a dialog, a sheet or a full screen, either at a moment the app
chooses (an interstitial) or when a template's button opens it ("Learn more"), one level deep,
from what is already on the device.

## Two ways in, one model

| | An interstitial | A screen a template opens |
|---|---|---|
| Opened by | the app, at its moment: `screens.maybeShow("app.launch")` | a tap on a `present` action |
| Decided by | the placement's rules (schedule, audience, arm, frequency cap) | the opening decision's `screens`, the app's data, or the placement's rules |
| Shown as | the rule's `presentation`, or the placement's default | the screen's `presentation`, or the placement's default |

**How** a screen shows is a presentation: `dialog` (centred over a scrim), `sheet` (along the
bottom) or `fullscreen`. The app lists each screen placement with the presentations it allows, so
a catalog or a server can never put a full screen where the app only allows a sheet:

```kotlin
val placements = listOf(
  ScreenPlacement("app.launch", "promo/details@1", setOf(Presentation.Fullscreen)),
  ScreenPlacement("promo.details", "promo/details@1", setOf(Presentation.Sheet, Presentation.Fullscreen)),
)
```

A screen's template is built for its presentation: its target's container is `screen:dialog`,
`screen:sheet` or `screen:fullscreen`, and `livery build` refuses one whose contract declares no
`dismiss` action, so every screen can be closed from itself as well as with back.

## Setting it up

```kotlin
val caps = { _: Presentation -> PlayerCapabilities(Surfaces.IN_APP, 7, setOf("androidx")) }
// Screens open only from what is on the device: prefetch their templates with the slots'.
val repository = TemplateRepository(…, targets = listOf(inAppCaps) + LiveryScreens.targets(placements, caps))
val rules = LocalRulesDecider({ repository.current() }, frequency)   // the bundled catalog until one downloads
val screens = LiveryScreens(planner, rules, { decisionContext() }, placements, caps, events, clock,
  appData = { placement, args -> if (placement == "promo.details") detailsFor(args["item"]) else null },
)
val actions = ActionRouter(screens.handlers(dismissInline = { plan, _, _ -> hideBanner(plan) }) + myHandlers, allowlist, events, clock)
val host = LiveryHost(repository, actions, events, clock)

setContent {
  App(host)
  LiveryScreenHost(screens, host)   // draws the screens over the app
}
// At the app's moment:
lifecycleScope.launch { screens.maybeShow("app.launch") }
```

In SwiftUI, `LiveryScreens(planner:placements:caps:context:decide:appData:recordImpression:)`
with `.liveryScreens(LiveryScreensModel(screens), actions:, events:) { input in … }` on the root
view (a full-screen cover on iOS, a sheet where the platform has none).

## An interstitial

A placement rule for a screen placement, with its presentation and a frequency cap:

```json
{ "id": "whats-new-7", "placement": "app.launch", "presentation": "fullscreen",
  "template": { "designId": "whats-new" }, "data": { "title": "What's new in 7.0" },
  "conditions": { "minAppVersion": 700 }, "frequency": { "maxImpressions": 1, "perSeconds": 31536000 } }
```

`maybeShow` shows it if its rules allow it now, the template is on the device, and no screen is
already showing; showing it counts towards its cap. An experiment arm can hold it back for a
control group (see [Analytics and experiments](../analytics-and-experiments.md)).

## A screen a template opens

The contract declares the action, the placement it opens and the arguments it carries:

```json
"details": { "kind": "present", "placement": "promo.details",
             "args": { "item": { "type": "string", "required": true, "maxLength": 40 } } }
```

Where the opened screen's data comes from, in order:

1. **The opening decision**: an ad or promotion ships its detail screen with it, so opening it
   needs no request: `"screens": { "details": { "template": { "designId": "details" }, "data": { … }, "presentation": "sheet" } }`.
   The screen's events carry the decision id `<decision>>details` and the opener's arms.
2. **The app's data** for the placement and the action's declared arguments (`appData`).
3. **The placement's rules.**

## One level deep

A screen opened from a slot, or by the app, may open one more (a dialog's "See all" opening a full
screen); a screen opened from a screen can't open another, and the tap is reported as a
`Fallback`. Closing a screen closes the one it opened.

## What gets reported

A screen is a slot to analytics: `Impression`, `Viewable`, `Action` and `Leave` (its time on
screen), plus a `Dismiss` with `detail` `action` (its own close), `system` (back, a swipe, a scrim
tap) or `replaced`. A screen that can't open (not allowed, not on the device, too deep) is a
`Fallback` with the reason.

## Full screens and insets

A full-screen template gets the window's safe-area insets as `livery.inset.top`, `.bottom`,
`.start` and `.end` (dp), named values the host writes like a Wear container's frame: read them to
pad around cut-outs and system bars. A contract needn't declare them.

## Widgets and notifications

A launcher widget, Wear widget or notification can't present a screen itself: its app handles the
tap (a `PendingIntent` or receiver) and calls `present` or `maybeShow` once it opens.

## Requires

Catalogs with `present` actions or screen rules require protocol level 5 (PROTOCOL.md §8.2), so
older clients keep their previous catalog.
