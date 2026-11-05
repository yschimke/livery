title: Live content
order: 13

# Live content

**In one line:** the app pushes its own live values (a thermostat, a score, a timer) into a
template, and people can install skins that draw the same data differently.

This is the Home Assistant case: cards are templates, entity states are the data, and skins are
designs people install.

## How it fits

- The **contract** names the app's own values (`name`, `state`, `is_on`, `level`).
- The **app** computes them and calls `SlotPlanner.forAppData` whenever they change; the player
  pushes the new values without restarting the document, so only updates animate.
- **Skins** are `OnRequest` designs: listed with previews, downloaded only when installed.

## 1. Draw with live data

```kotlin
val state by viewModel.kitchen.collectAsState()
LiverySlot(key = state, host = host, fallback = { NativeTile(state) }) {
  planner.forAppData(
    TemplateQuery("live/status-tile@1", inApp, designId = chosenSkin),
    buildJsonObject { put("name", state.name); put("state", state.label); put("is_on", state.on) },
  )
}
```

## 2. Let people pick a skin

```kotlin
val designs = rememberDesigns(repository, "live/status-tile@1", key = installedVersion)
DesignPicker(designs, onChoose = { d ->
  scope.launch { if (d.installed || repository.install(d.design.id)) chosenSkin = d.design.id }
}, preview = { d -> AsyncImage(d.previews.firstOrNull()?.url, null) })
```

`repository.preview(designId, query)` plays a design once without installing it, so the picker can
show it live with the person's own data.

## 3. Bundle a fallback catalog

Build the same catalog into the app's assets and pass it as `TemplateRepository.Bundled`, so a
first launch with no network still draws the built-in design.

## Deployment checklist

- [ ] The built-in design `Always`; skins `OnRequest`, with previews.
- [ ] A bundled catalog in the app's assets.
- [ ] A native fallback for each contract.
- [ ] The chosen design id persisted per slot.
