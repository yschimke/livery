title: Authoring templates
order: 21

# Authoring templates

**In one line:** design a template in **Compose code** (AndroidX `remote-creation-compose`) or in
**Compose UI Builder**, and either way it lands in the catalog the same: a `.rc` document and a
sidecar read from the document, checked against its contract before it is written.

Livery takes `.rc` bytes and a sidecar from anything (see [Concepts](concepts.md)). These two
flows are the supported ones: each starts from the contract, so a template can only read the
values and emit the actions its contract declares, and each records where the document came from.

| | Compose code (`livery-capture`) | Compose UI Builder (`livery-uibuilder`) |
|---|---|---|
| You write | a `@Composable` with `RemoteText`, `RemoteColumn`, … | a design on UI Builder's canvas |
| Contract values | `LiveryValues(contract).string("title")`, … | host inputs from `livery ui-builder scaffold` |
| A wrong name or type | fails the capture | refused by `livery ui-builder import` |
| Into the catalog | `DesignFolder.write` from a unit test | `livery ui-builder import` |
| Kept current by | a test that `DesignFolder.check`s the committed files | importing a newer UI Builder revision |
| `source` in the sidecar | `{ "tool": "capture", … }` | `{ "tool": "ui-builder", "design", "revision", "url" }` |
| Suits | developers, templates next to app code, CI | designers, quick iteration, agents over MCP |

Both end the same way: `livery build` (with `--inspect`) and `livery check --against origin/main`
in CI, then a signed release (see [Getting started](getting-started.md)).

## In Compose code

Add `livery-capture` (Android, since 0.2.0) to the module that holds your
template composables, and capture them from a Robolectric unit test:

```kotlin
dependencies {
  testImplementation("ee.schimke.composeai:livery-capture:<version>")
  testImplementation("org.robolectric:robolectric:4.17")
}
```

`LiveryValues` hands out the contract's values as named Remote Compose values, with the
contract's defaults, and its actions as host actions. A name the contract doesn't declare, or the
wrong type, throws, so the capture fails rather than the publish:

```kotlin
@Composable @RemoteComposable
fun StatusTile(v: LiveryValues) {
  RemoteColumn(RemoteModifier.fillMaxSize().clickable(v.action("toggle"))) {
    RemoteText(v.string("name"), color = v.color("theme.primary"))
    RemoteText(v.string("state"))
  }
}
```

Capture each target once and write or check the design's folder:

```kotlin
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35], qualifiers = "mdpi")
class StatusTileTemplates {
  private val catalog = File("../templates")          // your catalog source tree
  private val contract = LiveryCapture.contract(catalog, "live/status-tile@1")

  @Test fun designFolderIsCurrent() = runBlocking {
    val context = RuntimeEnvironment.getApplication()
    val launcher = Target("launcher-widget", 7, listOf("widgets"))
    val templates = mapOf(
      "launcher" to LiveryCapture.capture(context, launcher, 200, 100) { StatusTile(LiveryValues(contract)) },
    )
    val folder = DesignFolder(File(catalog, "designs/tile-default"))
    if (System.getProperty("livery.capture.update") == "true") folder.write(templates)
    else assertEquals(emptyList(), folder.check(templates))
  }
}
```

- `LiveryCapture.capture` uses the widgets profile for a target with the `widgets` profile. The
  sidecar's `reads` and `emits` come from the captured document, never from hand-written lists.
- `DesignFolder.write` writes `<name>.rc` and `<name>.json` and removes templates no longer
  captured; `check` lists what differs, so CI fails when a composable changed and its catalog
  files didn't. Forward the switch in Gradle with
  `tasks.withType<Test> { systemProperty("livery.capture.update", findProperty("livery.capture.update") ?: "false") }`.
- Lists are `"<list>.<i>.<field>"` with `v.int("<list>.count")`; theme roles are `theme.*`
  colours the host fills.
- `v.action(name, args)` sends the arguments the action declares, checked when you capture; a
  `track` action reports a tap that only changes the template's own state, combined with it (see
  [Interaction tracking](interaction-tracking.md)).

## In Compose UI Builder

**1. Start from the contract.** Turn it into UI Builder host inputs: state variables with
`"persistence": "host"`, named exactly as Livery binds them, with the contract's defaults as their
initial values:

```bash
livery ui-builder scaffold templates --contract live/status-tile@1 > host-inputs.json
```

```json
{ "stateVariables": {
    "is_on": { "type": "value", "valueType": "bool", "initialValue": false, "nullable": false, "persistence": "host" },
    "name":  { "type": "text", "valueType": "string", "initialValue": "", "nullable": false, "persistence": "host" } } }
```

Add them to the design's `stateVariables` and bind controls to them. A host input is read-only in
the design; a tap goes to the host as a host action named like the contract's action. Theme roles,
colours and images aren't host inputs (the command lists what it left out). Lists get
`<list>.count` and every item up to the list's `max` (`--items <n>` for fewer).

**2. Import a revision into the catalog:**

```bash
UI_BUILDER_TOKEN=… livery ui-builder import kitchen-tile templates \
  --as tile-default/launcher \
  --target '{"surface":"launcher-widget","rcApiLevel":7,"profiles":["widgets"]}' \
  --api https://<ui builder host> --revision 42
```

It exports the design (its latest revision without `--revision`), reads what the document reads
and emits, refuses it when that doesn't fit the design's contract, writes
`designs/tile-default/launcher.rc` and its sidecar with
`source: {tool: ui-builder, design, revision, url}`, and builds the whole tree, undoing the import
if the catalog no longer builds. To move a template to a newer revision, import that revision and
commit the change.

**With an agent**: `livery-mcp --ui-builder https://<ui builder host>` offers the same two steps
as tools, `ui_builder_scaffold` and `ui_builder_export` (see [AI workflows](ai-workflows.md)).

## Any other tool

`livery import` puts any document into a design, with the same checks and a sidecar read from the
document:

```bash
livery import build/tile.rc templates --as tile-default/in-app \
  --target '{"surface":"in-app","rcApiLevel":7}' --source tool=my-exporter --source commit=$(git rev-parse HEAD)
```

`livery ui-builder import` is this command with UI Builder's export in front of it.
