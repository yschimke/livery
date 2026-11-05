title: Wear widgets
order: 15

# Wear widgets

> **Planned.** The pieces exist; the host module doesn't yet.

**In one line:** the same Livery templates in Glance Wear widget containers, instantiated per
container on the watch from blobs the phone syncs.

## The plan

- Templates target `"surface": "wear-widget"` with `"container": "wear:small"` or `"wear:large"` and
  a baked dark theme.
- Instantiation runs `livery-instantiate` plus rc-players' `RcWearTemplates.instantiate`, which
  frames the template in the container and fills its padding and corner radius.
- Taps go through rc-players' `RcHostActionRewrites.toPendingIntent`.
- The phone downloads and verifies the blobs, then sends them to the watch over the Data Layer as
  content-addressed assets. An LTE watch can run its own repository. The watch app bundles its
  own fallback catalog.

Until the module lands, `TemplateInstantiator` already produces Wear-ready documents for a
`GlanceWearWidget` you write yourself.
