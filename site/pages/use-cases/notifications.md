title: Notifications
order: 16

# Notifications

**In one line:** ask `LiveryNotifications` for a notification's body, set it as the custom
expanded view, and taps come back to your `LiveryNotificationReceiver`.

Android 16 (API 36) and later play Remote Compose documents in a notification's `RemoteViews`, as
launchers do for widgets; older devices get no body, and you post your plain notification. Built
on rc-players' `rc-widget-launcher`.

## How it fits

SystemUI can't receive pushed values, so each time you post or update the notification:

1. you plan the template and data for the body (`NotificationSlot`: the shade's width, at most
   256dp tall, which Android caps a custom expanded body at);
2. Livery writes the values and images into the template (`livery-instantiate`) and rewrites its
   named taps into the numeric actions SystemUI sends;
3. you set the result with `setCustomBigContentView` and post.

Nothing waits on the network: a template or image that isn't on the device yet gives no body, and
`fetchMissed()` downloads it so you can post the notification again with the same id.

## 1. Publish a notification template

```json
{ "target": { "surface": "notification", "rcApiLevel": 7, "profiles": ["widgets"],
              "size": { "minWidthDp": 280, "maxWidthDp": 600, "minHeightDp": 64, "maxHeightDp": 256 } },
  "reads": ["title", "state"], "emits": ["toggle", "open"] }
```

Keep the instantiated document small (`documentMaxBytes`, 50 000 by default); templates may be up
to the capabilities' `maxBytes` (500 000). A conditional layout keeps every branch in the
document, as for [launcher widgets](launcher-widgets.md).

## 2. Render and post

```kotlin
val notifications = LiveryNotifications(images = app.images) // an ImageResolver

suspend fun post(context: Context, id: Int, data: JsonObject) {
  val render = notifications.render(
    context, id,
    plan = { slot -> app.planner.forAppData(slot.query("live/status-card@1"), data) },
    tapIntent = { actionId, action ->
      if (action == "open") RcLauncherWidgets.actionActivity(context, launchIntent(context), id, actionId)
      else LiveryNotificationReceiver.tapBroadcast(context, StatusTaps::class.java, id, actionId, action)
    },
  )
  val builder = NotificationCompat.Builder(context, CHANNEL)
    .setSmallIcon(R.drawable.ic_status)
    .setContentTitle(data.title)
    .setContentText(data.summary)
  // views come wrapped so SystemUI's template can nest them (Android 17 refuses a nested view with
  // draw instructions): post them as they are, with no setStyle() alongside.
  render.views?.let { builder.setCustomBigContentView(it) }
    ?: builder.setStyle(NotificationCompat.BigTextStyle().bigText(data.summary))
  NotificationManagerCompat.from(context).notify(id, builder.build())
  if (render.hasMisses && render.fetchMissed()) post(context, id, data)
}
```

Keep the title and text on every notification: the collapsed view, the lock screen and a paired
watch show those, not the body.

## 3. Handle taps

```kotlin
class StatusTaps : LiveryNotificationReceiver() {
  override suspend fun onTap(context: Context, notificationId: Int, tap: WidgetTap) {
    if (tap.action == "toggle") app(context).toggle(tap.args["entity"])
  }
}
```

Declare it in the manifest with `android:exported="false"`. Opening the app is an activity, which
a broadcast receiver may not start, so build that tap with `RcLauncherWidgets.actionActivity`.

Add `LiveryNotifications.DEFAULT_CAPABILITIES` to the repository's `targets` so notification
templates download with every refresh, before the first notification needs one.

## Deployment checklist

- [ ] A `notification` template per design; its instantiated document inside `documentMaxBytes`.
- [ ] A title and text on every notification, and a plain style when `views` is null.
- [ ] No style set alongside the custom body.
- [ ] A `LiveryNotificationReceiver` in the manifest; opening taps as activities.
- [ ] The notification capabilities in the repository's `targets`.

> Not yet verified on a device in CI.
