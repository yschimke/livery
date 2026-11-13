title: Authoring templates
order: 21

# Authoring templates

**In one line:** design a template in **Compose code** (rc-players' Compose Multiplatform creation
API on any JVM, or AndroidX `remote-creation-compose` on Android) or in **Compose UI Builder**, and
either way it lands in the catalog the same: a `.rc` document and a sidecar read from the
document, checked against its contract before it is written.

Livery takes `.rc` bytes and a sidecar from anything (see [Concepts](concepts.md)). These two
flows are the supported ones: each starts from the contract, so a template can only read the
values and emit the actions its contract declares, and each records where the document came from.

| | Compose code (`livery-capture-cmp`, `livery-capture`) | Compose UI Builder (`livery-uibuilder`) |
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

Remote Compose has two creation APIs with the same shape, and Livery has a binding for each. Both
give you the same `LiveryValues` and `LiveryCapture`, over one shared core (`livery-capture-core`:
`CapturedTemplate`, `DesignFolder`, `CatalogContracts`), and a template captured through either
is the same template to Livery.

| | `livery-capture-cmp` | `livery-capture` |
|---|---|---|
| Creation API | rc-players' Compose Multiplatform one: `ee.schimke.composeai:rc-creation-compose`, package `ee.schimke.composeai.rccreation.*` | AndroidX's: `androidx.compose.remote:remote-creation-compose` (alpha), package `androidx.compose.remote.creation.*` |
| Runs on | a plain JVM (a unit test, a build tool, a server), Android; compiled for Wasm, not yet tested in a browser | Android only: a Robolectric unit test |
| Livery package | `ee.schimke.livery.capture.cmp` | `ee.schimke.livery.capture` |
| Capture | `LiveryCapture.capture(target, widthDp, heightDp, profile = …) { … }` | `LiveryCapture.capture(context, target, widthDp, heightDp, profile = …) { … }` |
| A `widgets` target | captured under `RcCaptureProfiles.launcherWidgets` (`rc-widget-creation`) | captured under `rc-widget-launcher`'s `widgetsProfile`, the same operations and header |
| Available | from the release after 0.9.0 | since 0.2.0 |

**Use `livery-capture-cmp`** unless something forces the other: it needs no Android SDK, no
Robolectric and no AndroidX alpha, so templates build wherever Kotlin does. **Use
`livery-capture`** when the template composes with a library built on AndroidX's creation API
(Wear `remote-material3`, your own AndroidX-typed components), or shares composables with content
you hand to `rc-widget-launcher` or `rc-widget-wear`, which take AndroidX's types. An app can hold
both: the two APIs are in different packages, and so are the two bindings.

The two APIs are the same code at different ages: rc-players' follows androidx-main, AndroidX's
alpha is a release behind it. A few names differ until the alpha catches up, for example
`fontWeight = RemoteFontWeight.Bold` and `valueChangeAction(…)` on the first where the alpha has
`FontWeight.Bold` and `valueChange(…)`. `LiveryValues` is identical on both.

```kotlin
dependencies {
  // rc-players' Compose Multiplatform creation API: a Kotlin/JVM or multiplatform module.
  testImplementation("ee.schimke.composeai:livery-capture-cmp:<version>")
  // or AndroidX's, in an Android module:
  testImplementation("ee.schimke.composeai:livery-capture:<version>")
  testImplementation("org.robolectric:robolectric:4.17")
}
```

`LiveryValues` hands out the contract's values as named Remote Compose values, with the
contract's defaults, and its actions as host actions. A name the contract doesn't declare, or the
wrong type, throws, so the capture fails rather than the publish:

```kotlin
import ee.schimke.composeai.rccreation.compose.layout.*      // or androidx.compose.remote.creation.compose.layout.*
import ee.schimke.composeai.rccreation.compose.modifier.*
import ee.schimke.livery.capture.cmp.LiveryValues            // or ee.schimke.livery.capture.LiveryValues

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
(`catalog.json`, `contracts/`, each `design.json`). With `livery-capture-cmp` that is an ordinary
JVM test:

```kotlin
class StatusTileTemplates {
  private val catalog = File("../templates")          // your catalog source tree
  private val contract = CatalogContracts.contract(catalog, "live/status-tile@1")

  @Test fun captureTileDefault() = runBlocking {
    val launcher = Target("launcher-widget", 7, listOf("widgets"))
    val templates = mapOf(
      "launcher" to LiveryCapture.capture(launcher, 200, 100) { StatusTile(LiveryValues(contract)) },
    )
    val out = System.getProperty("livery.capture.out") ?: return@runBlocking  // set by captureTemplates
    DesignFolder(File(out, "designs/tile-default")).write(templates)
  }
}
```

With `livery-capture` the same test runs under Robolectric and hands the capture a `Context`:

```kotlin
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35], qualifiers = "mdpi")
class StatusTileTemplates {
  private val catalog = File("../templates")
  private val contract = LiveryCapture.contract(catalog, "live/status-tile@1")

  @Test fun captureTileDefault() = runBlocking {
    val context = RuntimeEnvironment.getApplication()
    val launcher = Target("launcher-widget", 7, listOf("widgets"))
    val templates = mapOf(
      "launcher" to LiveryCapture.capture(context, launcher, 200, 100) { StatusTile(LiveryValues(contract)) },
    )
    val out = System.getProperty("livery.capture.out") ?: return@runBlocking
    DesignFolder(File(out, "designs/tile-default")).write(templates)
  }
}
```

```kotlin
// build.gradle.kts: captures into build/, then the source tree plus the captures as build/catalog.
val captured = layout.buildDirectory.dir("captured-templates")
val captureTemplates by tasks.registering(Test::class) {
  val unitTest = tasks.named<Test>("test")   // "jvmTest" in a multiplatform module, "testDebugUnitTest" in an Android one
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
to an output branch. Livery's own samples work this way, on the plain JVM:
`./gradlew :livery-capture-cmp:sampleCatalogs` captures `banner-classic`, `poll-card` and
`quantity-stepper` into `livery-capture-cmp/build/samples/`.

- **A target is captured under a profile.** Both bindings take a capture `profile`, which the
  writer holds the document to: it refuses an operation outside it (for example `Operation 240 is
  not supported for this version`), writes text as `TextLayout` where the profile has no `CoreText`,
  and writes the profile's header. Nothing is rewritten afterwards. The default follows the
  target's `profiles`: `widgets` (a launcher widget, a notification) is
  `RcCaptureProfiles.launcherWidgets`, the operations Android 16's level 6 player and API 37's both
  decode (what `livery-capture` calls `widgetsProfile`); `glance-wear` is
  `RcCaptureProfiles.wearWidgets`, Glance Wear's allow-list; `experimental` is
  `RcCaptureProfiles.androidXExperimental`, for an app's own player (a flow row, a pattern); anything
  else is the creation default, `RcPlatformProfiles.ANDROIDX`. A launcher capture does not hold
  `TextSubtext` (a string cut with `substring`, which a relative time uses), because
  `launcherWidgets` follows Android 16's level 6 operation table, which lists none. For API 37 launchers only pass `RcCaptureProfiles.systemThemedLauncherWidgets`, or build the
  profile with `RcTargetProfile.of(7, …).toCaptureProfile(without = …)`.
- **A capture is a template, not a playable document.** A padding, a spacing or a background
  colour that reads a named value or the display's density is fine: the host writes the values in
  (`livery-instantiate`) and `rc-widget-launcher` makes what Android 16 reads only as literals
  literal. The capture checks only what instantiation can't change, such as an operation outside the
  profile, or a `drawWithContent` (or a brush background) on an Android 16 launcher, which that
  player paints until the widget host dies. `livery-verify` plays an instantiated capture on
  Android 16's own player.
- `livery-capture`'s `LiveryCapture.capture` uses `rc-widget-launcher`'s widgets profile for a
  target with the `widgets` profile and the creation default otherwise; pass Glance Wear's own
  `profile` for a `glance-wear` one.
- The sidecar's `reads` and `emits` come from the captured document, never from hand-written lists.
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

Designs written against rc-players' Compose Multiplatform creation API (`livery-capture-cmp`)
capture on a plain JVM, with no Robolectric, so they need no test to run in. The catalog plugin runs
the capture for you: give it the entry point, a `main` called with `<source> <out>`, and it writes
the catalog tree into `<out>` from the hand-written data in `<source>` (`catalog.json`, themes,
placements, fonts) plus the captured `designs/` and `contracts/`.

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
`buildLiveryCatalog` then builds what it captured. A design library on AndroidX's creation API
(`livery-capture`) still captures from a Robolectric test, as above; a plugin runner for that is
*Planned*.

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
