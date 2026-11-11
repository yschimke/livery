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
| Into the catalog | `DesignFolder.write` from a unit test, at build time | `livery ui-builder import` |
| Kept current by | the build: the `.rc` is build output, never committed | importing a newer UI Builder revision |
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

Commit the Kotlin, not the `.rc`: capture each target once and write the design's folder into the
build directory, where a Gradle task lays it over the hand-written catalog source tree
(`catalog.json`, `contracts/`, each `design.json`):

```kotlin
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35], qualifiers = "mdpi")
class StatusTileTemplates {
  private val catalog = File("../templates")          // your catalog source tree
  private val contract = LiveryCapture.contract(catalog, "live/status-tile@1")

  @Test fun captureTileDefault() = runBlocking {
    val context = RuntimeEnvironment.getApplication()
    val launcher = Target("launcher-widget", 7, listOf("widgets"))
    val templates = mapOf(
      "launcher" to LiveryCapture.capture(context, launcher, 200, 100) { StatusTile(LiveryValues(contract)) },
    )
    val out = System.getProperty("livery.capture.out") ?: return@runBlocking  // set by captureTemplates
    DesignFolder(File(out, "designs/tile-default")).write(templates)
  }
}
```

```kotlin
// build.gradle.kts: captures into build/, then the source tree plus the captures as build/catalog.
val captured = layout.buildDirectory.dir("captured-templates")
val captureTemplates by tasks.registering(Test::class) {
  val unitTest = tasks.named<Test>("testDebugUnitTest")
  testClassesDirs = files(unitTest.map { it.testClassesDirs })
  classpath = files(unitTest.map { it.classpath })
  filter { includeTestsMatching("*.StatusTileTemplates") }
  inputs.dir("templates/contracts").withPathSensitivity(PathSensitivity.RELATIVE)
  outputs.dir(captured)
  systemProperty("livery.capture.out", captured.get().asFile.absolutePath)
}
val catalog by tasks.registering(Sync::class) {
  into(layout.buildDirectory.dir("catalog"))
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  from(files(captured).builtBy(captureTemplates))
  from("templates")
}
```

Point `livery build` (or the Gradle plugin's `livery { source }`) at `build/catalog`, or publish it
to an output branch. Livery's own samples work this way: `./gradlew :livery-capture:sampleCatalogs`
captures `banner-classic`, `poll-card` and `quantity-stepper` into
`livery-capture/build/samples/`.

- `LiveryCapture.capture` uses the widgets profile for a target with the `widgets` profile. The
  sidecar's `reads` and `emits` come from the captured document, never from hand-written lists.
- `DesignFolder.write` writes `<name>.rc` and `<name>.json` and removes templates no longer
  captured. A catalog repository that does commit its `.rc` (another tool's output, say) can keep
  them current with `check`, which lists what differs from a capture.
- Lists are `"<list>.<i>.<field>"` with `v.int("<list>.count")`; theme roles are `theme.*`
  colours the host fills.
- `v.action(name, args)` sends the arguments the action declares, checked when you capture; a
  `track` action reports a tap that only changes the template's own state, combined with it (see
  [Interaction tracking](interaction-tracking.md)).
- `v.set(name, value)` sends a `set` action: the person changed the input it names (a toggle, a
  stepper) to `value`, which the document computes when tapped, as in
  `v.set("setQuantity", v.int("quantity") + 1)`. The host writes it back and the new value comes
  back in as the input, so the template keeps no state of its own. `set` is checked against the
  input's type when you capture; a value past the input's limits is refused by the client. A
  catalog with a `set` needs protocol level 7. See
  [`samples/two-way`](https://github.com/yschimke/livery/tree/main/samples/two-way).

### Capturing on the JVM with the Gradle plugin

Designs written against rc-players' Compose Multiplatform fork of `remote-creation-compose` capture
on a plain JVM, with no Robolectric. The catalog plugin runs the capture for you: give it the entry
point, a `main` called with `<source> <out>`, and it writes the catalog tree into `<out>` from the
hand-written data in `<source>` (`catalog.json`, themes, placements, fonts) plus the captured
`designs/` and `contracts/`.

```kotlin
plugins {
  kotlin("jvm")
  id("ee.schimke.composeai.livery.catalog") version "<version>"
}

livery {
  source.set(layout.projectDirectory.dir("catalog"))   // the hand-written data only
  capture {
    mainClass.set("com.example.catalog.CaptureKt")     // main(args): args[0] source, args[1] out
    jvmArgs.add("-Djava.awt.headless=true")             // optional
  }
}
```

`captureLiveryCatalog` runs it in its own JVM on the project's runtime classpath (set
`capture.classpath` for another), into `build/livery/captured`, emptied first. It is cacheable:
it re-runs only when the designs' code, the entry point or the hand-written tree changes.
`buildLiveryCatalog` then builds what it captured. An Android-only design library still captures
from a Robolectric test, as above; a plugin runner for that is *Planned*.

### Previews of every template

`renderLiveryPreviews` draws every template in the catalog (the captured tree, when there is a
capture) with nothing written by hand. What the tree already says decides what it draws:

- **Data**: each of the contract's `examples`, bound through `ContractBinder` as on a device. An
  example that doesn't bind fails the task, after the rest are drawn.
- **Modes**: a `host`-themed template in `light` and `dark` (Material 3's baseline scheme bound to
  its `theme.*` roles, as an app pushes its own) and under each theme pack in `themes/`
  (`theme-<id>`); a `baked:dark` or `baked:light` template once, in its own mode.
- **Size**: the document's own (the size it was captured at), kept inside its target's range.
- **Background**: the scheme's or theme pack's surface, else the mode's (black on a watch).
- **Fonts** from `fonts/fonts.json`, and one placeholder for every image: no network.

```bash
./gradlew renderLiveryPreviews   # build/livery/previews/index.html
```

It writes `<design>/<template>/<example>/<mode>.png`, a `previews.json` index and an `index.html`
that shows them all. Each picture comes from Compose Multiplatform's player on Skia (`livery-render`),
in its own JVM. The task resolves `ee.schimke.composeai:livery-render` at the plugin's version from
the build's repositories; set `previews { classpath.from(…) }` to use another. It is cacheable.

The matrix itself is data too. Every run writes `cases.json`, with the documents it names under
`documents/` and the tree's `fonts/` beside them. Each case gives the document, its target (surface,
profiles, container, theme), its size in dp, light or dark, the background behind it, and the named
values to bind: the example's data and the mode's `theme.*` roles, named as the contract names them.
Any renderer can play the cases, not only this one. Set `previews { draw.set(false) }` to export the
cases without drawing them.

Beside it, `remote-document-cases.json` holds the same cases in compose-preview's shared shape
(`RemoteDocumentCasesV1`, in compose-preview-contracts). Each case has an `id`
(`<design>/<template>/<example>/<mode>`), its target as a player profile (`androidx7`, `widgetsV7`,
`wearWidgets`), and its values in the daemon's `kind` form; an image value is left at the document's
default. compose-preview's `render-cases` draws that file with each surface's own player (the AndroidX
player, a launcher widget's `RemoteViews`, Glance Wear) under Robolectric. That command is *Planned*:
it is not released yet.

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
