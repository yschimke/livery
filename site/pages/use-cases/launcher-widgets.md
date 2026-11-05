title: Launcher widgets
order: 14

# Launcher widgets

**In one line:** subclass `LiveryLauncherWidgetProvider`, return a plan for the widget's cell, and
Livery instantiates the template at that size, publishes it, and routes taps back to you.

Android 16 (API 36) and later play Remote Compose documents in `RemoteViews` draw instructions;
older launchers get your fallback. Built on rc-players' `rc-widget-launcher`.

## How it fits

A launcher can't receive pushed values, so on every update and resize the provider:

1. asks you for a plan for the widget's cell (`WidgetSlot` with its dp size);
2. writes the values and images into the template (`livery-instantiate`). Updates never wait on
   the network: a template or image that isn't on the device yet draws your fallback, is fetched in the
   background, and the widget updates again;
3. rewrites the template's named taps into the numeric actions a launcher sends;
4. publishes the result.

## 1. Publish a widget template

Give the design a template for the launcher, sized for widget cells:

```json
{ "target": { "surface": "launcher-widget", "rcApiLevel": 7, "profiles": ["widgets"],
              "size": { "minWidthDp": 110, "maxWidthDp": 400, "minHeightDp": 40, "maxHeightDp": 400 } },
  "reads": ["name", "state"], "emits": ["toggle"] }
```

Keep what the launcher gets small: launchers expect documents of a few tens of kilobytes
(`documentMaxBytes`, 50 000 by default). That is the instantiated document. The template can hold
a conditional layout (`RemoteStateLayout` on a named value) with a branch for each state:
instantiation writes the values in and the launcher's player draws the live branch. Every branch
stays in the document, so count them all against `documentMaxBytes` (rc-players drops a branch only
when it defines no values, which a Compose capture's branches always do). Templates may be up to
the capabilities' `maxBytes` (500 000 by default).

## 2. The provider

```kotlin
class TileWidget : LiveryLauncherWidgetProvider() {
  override suspend fun plan(context: Context, slot: WidgetSlot): PlanResult =
    app(context).planner.forAppData(slot.query("live/status-tile@1"), app(context).tileData())

  override fun images(context: Context) = app(context).images // an ImageResolver

  override suspend fun onTap(context: Context, widgetId: Int, tap: WidgetTap) {
    if (tap.action == "toggle") app(context).toggle()
    updateAll(context, TileWidget::class.java)
  }

  override fun fallback(context: Context, widgetId: Int, reason: String) =
    RemoteViews(context.packageName, R.layout.tile_widget_fallback)

  // Opening the app is an activity, which a broadcast receiver may not start.
  override fun tapIntent(context: Context, widgetId: Int, actionId: Int, action: String) =
    if (action == "open") RcLauncherWidgets.actionActivity(context, launchIntent(context), widgetId, actionId)
    else super.tapIntent(context, widgetId, actionId, action)
}
```

Register it like any `AppWidgetProvider` in the manifest, with an `appwidget-provider` XML. Call
`LiveryLauncherWidgetProvider.updateAll(context, TileWidget::class.java)` when the data or the
catalog changes.

## 3. Make sure the template is on disk

Add the launcher's capabilities to the repository's `targets`, so `Always` and installed designs
download their launcher templates with every refresh, before anyone places a widget:

```kotlin
val launcher = PlayerCapabilities(Surfaces.LAUNCHER_WIDGET, 7, setOf("widgets"), livePush = false, maxBytes = 500_000)
TemplateRepository(..., targets = listOf(inApp, launcher))
```

## Deployment checklist

- [ ] A `launcher-widget` template per design and size tier; its instantiated document inside
      `documentMaxBytes`.
- [ ] Taps that open the app go through `tapIntent` as activities.
- [ ] The launcher in the repository's `targets`.
- [ ] A `RemoteViews` fallback for Android 15 and older, and for failures.
- [ ] `updateAll` on data and catalog changes.
- [ ] Images through `ImageResolver`; the widget can load nothing itself.

> Not yet verified on a device in CI. Taps carry a template action's text value as metadata;
> numeric action values are dropped on launchers.
