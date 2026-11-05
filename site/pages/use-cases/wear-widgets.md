title: Wear widgets
order: 15

# Wear widgets

**In one line:** subclass `LiveryWearWidget`, return a plan for the widget's container, and Livery
instantiates the template for that container on that watch and turns its taps into your
`PendingIntent`s.

Built on Glance Wear (`androidx.glance.wear` 1.0.0-alpha21) and rc-players' `rc-widget-core` Wear
frames. Module: `livery-wear-widgets`.

## How it fits

A Wear widget host can't receive pushed values or load anything, so each time the host asks for a
render the widget:

1. asks you for a plan for the widget's container (`WearWidgetSlot`: its type, size, padding and
   corner radius as the host reports them);
2. writes the values and images into the template (`livery-instantiate`). Renders never wait on
   the network: an image that isn't on the watch yet draws your fallback, is fetched in the
   background, and the widget is asked to render again;
3. frames it in the container: the container's padding, corners and fill go into the template's
   `wear.container.*` named values, the watch's density and font scale are folded in, and anything
   the Wear widget player can't read is rewritten (rc-players' `RcWearTemplates.instantiate`);
4. turns each distinct tap into a `PendingIntent` you build, since that is the only action the host
   runs.

## 1. Publish Wear templates

One template per container type, with a baked dark theme:

```json
{ "target": { "surface": "wear-widget", "rcApiLevel": 7, "profiles": ["glance-wear"],
              "container": "wear:small", "theme": "baked:dark" },
  "reads": ["name", "state", "is_on"], "emits": ["toggle"] }
```

Draw the container's padding, corner radius and fill from the `wear.container.horizontal_padding`,
`wear.container.vertical_padding`, `wear.container.corner_radius` and `wear.container.color` named
values (rc-players' `RcWearContainer`), so one capture fits every watch's containers.

Taps must have constant arguments, such as `{"entity":"light.kitchen"}`. A `PendingIntent` is fixed
when the widget is published, so a tap whose arguments are an expression can't be carried; it is
left out (the host ignores it).

## 2. The widget

```kotlin
abstract class TileWidget : LiveryWearWidget() {
  override suspend fun plan(context: Context, slot: WearWidgetSlot): PlanResult =
    app(context).planner.forAppData(slot.query("live/status-tile@1"), app(context).tileData())

  override fun images(context: Context) = app(context).images // an ImageResolver

  override fun tapIntent(context: Context, instanceId: Int, index: Int, tap: WidgetTap): PendingIntent =
    PendingIntent.getBroadcast(
      context, instanceId * 100 + index,
      Intent(context, TileTapReceiver::class.java)
        .putExtra("action", tap.action)
        .putExtra("args", Bundle().apply { tap.args.forEach { (k, v) -> putString(k, v) } }),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

  override fun fallback(context: Context, slot: WearWidgetSlot, reason: String): WearWidgetData =
    WearWidgetDocument(WearWidgetBrush.color(Color.DarkGray.rc)) { RemoteText("Open the app".rs) }
}

class TileSmallWidget : TileWidget()
class TileLargeWidget : TileWidget()

@AssociateWithGlanceWearWidget(TileSmallWidget::class)
class TileSmallWidgetService : GlanceWearWidgetService() { override val widget = TileSmallWidget() }

@AssociateWithGlanceWearWidget(TileLargeWidget::class)
class TileLargeWidgetService : GlanceWearWidgetService() { override val widget = TileLargeWidget() }
```

`TileTapReceiver` runs the action, usually through an `ActionRouter`, then calls
`TileSmallWidget().triggerUpdateAll(context)` (and the same for the large widget) so the widgets
show the new state.

Declare each service in the manifest with `androidx.glance.wear.action.BIND_WIDGET_PROVIDER` and a
`wearwidget-provider` descriptor that names its container type. Glance Wear's picker offers one
container type per service.

## 3. Make sure the template is on the watch

Rendering never waits on the network, so the blob must already be in the watch's repository when
the host asks:

- **An LTE or Wi-Fi watch** runs its own `TemplateRepository` with both containers in `targets`:

  ```kotlin
  val small = PlayerCapabilities(Surfaces.WEAR_WIDGET, 7, setOf("glance-wear"), livePush = false,
    themes = setOf("baked:dark"), container = "wear:small")
  TemplateRepository(..., targets = listOf(small, small.copy(container = "wear:large")))
  ```

- **A watch that relies on its phone** gets the verified blobs from the phone app over the Data
  Layer. *Planned:* a Livery Data Layer source. Until then, write the blobs the phone sends into
  the watch's `BlobStore` and the manifest into a `BundledSource`.

## Deployment checklist

- [ ] A `wear-widget` template for each of `wear:small` and `wear:large`, drawing its container
      from the `wear.container.*` named values.
- [ ] Taps with constant arguments only.
- [ ] One widget subclass and service per container type, each with its `wearwidget-provider`.
- [ ] A fallback for a widget whose template isn't on the watch yet.
- [ ] Blobs on the watch before the widget is placed (watch repository or phone sync).
- [ ] `triggerUpdateAll` after a tap or a data change.

> Not yet verified on a watch in CI. The document pipeline is covered by `livery-instantiate`'s
> tests against a real tile document.
