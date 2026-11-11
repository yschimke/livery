# Capturing templates from Kotlin: a Gradle plugin, KSP, and (not yet) a compiler plugin

*Status: step 1's JVM runner is built (`livery { capture { … } }`, `CaptureLiveryCatalog`). The
Android unit-test runner, step 2 and step 3 are proposals, marked Planned on the site until built.*

A Livery template's source is a Kotlin `@RemoteComposable` function. The `.rc` document is build
output (AGENTS.md). This note asks how that build should work, and whether it should be a Kotlin
compiler plugin, the way [Metro](https://github.com/ZacSweers/metro) is for dependency injection.

## Where things are today

Two apps capture templates, two different ways:

| | Confetti | Livery's own samples |
| --- | --- | --- |
| Authoring library | rc-players' Compose Multiplatform creation API (`rc-creation-compose`, JVM) | the same, through `livery-capture-cmp` (they were AndroidX `remote-creation-compose` under Robolectric until rc-players 3.0; `livery-capture` still captures them that way, for `bindingAgreementTest`) |
| Runs as | a `JavaExec` of the app's own `main` (`generateLiveryCatalog`), cacheable, inputs and outputs declared | plain JVM tests filtered to `SampleCatalogsTest` (`captureSamples`), writing when `livery.capture.out` is set |
| What lists the designs | a hand-written `AllDesigns` list of `DesignDef(id, contract, targets, tags, …)` | one test per design |
| Contract checks | at compile time through a typed DSL (Kotlin 2.4 context parameters: a design in `context(ContractScope<SessionContract>)` can only read that contract's inputs); again at capture | at capture time (`LiveryValues` checks every name and type against the contract JSON) |
| Then | `BuildLiveryCatalog` (this repo's Gradle plugin) checks and bundles; `livery build` publishes | `sampleCatalogs` lays captures over the hand-written tree; `livery build` |

Each app has rebuilt the same three pieces: **run the capture**, **find the designs**, and
**check them against the contract**.

## Can a compiler plugin do this?

Partly, and not the part that costs most.

- **Capture needs code to run.** A `.rc` document is what a composable *draws*: capture runs the
  composition against a recording canvas. A compiler plugin runs inside `kotlinc` with no Compose
  runtime and no app classpath, so it cannot produce the document. Whatever else changes, capture
  stays a build step that executes the compiled code.
- **Finding the designs is code generation**, not a code transformation. Metro needs a compiler
  plugin because it validates a dependency graph across modules and generates IR in the same pass,
  which the type system can't express and KSP can't do in one round. Collecting annotated
  functions into a registry is what KSP does, incrementally, on stable APIs.
- **Contract checks are mostly expressible in types.** Confetti shows it: a design written against
  a contract type can only name that contract's inputs and actions, and a misspelt or foreign name
  doesn't compile. What types can't catch (a list index past `max` when it's a constant, an action
  emitted without its required argument) is small, and capture already refuses it.

A compiler plugin would tie every app to the exact Kotlin version Livery's plugin is built for (the
K2 FIR and IR APIs are not stable), and needs separate IDE support for its diagnostics. That cost
buys little here.

## Proposal

Three steps, each useful on its own, in this order.

### 1. Capture in the Gradle plugin

`ee.schimke.composeai.livery.catalog` gains a capture task, so an app no longer writes a `JavaExec`
or a test filter:

```kotlin
livery {
  capture {
    // Where the designs are: a module of @RemoteComposable functions.
    designs.from(project(":designs"))
    runner.set(LiveryCaptureRunner.Jvm)        // or AndroidUnitTest (Robolectric)
  }
  source.set(layout.projectDirectory.dir("catalog"))  // the hand-written data, as today
}
```

- It runs in a Gradle worker with classloader or process isolation, with the designs' runtime
  classpath and the hand-written tree as inputs and `build/livery/catalogs` as its output:
  cacheable and safe with the configuration cache. `BuildLiveryCatalog` then reads that output.
- The entry point is one interface in `livery-capture-core`, which each runner discovers with
  `ServiceLoader`: `LiveryCatalogSource { fun designs(): List<CapturableDesign> }`. Confetti's
  `AllDesigns` and the sample tests are two implementations.
- Both runners are needed. A JVM worker can't load AndroidX `remote-creation-compose` (the
  `livery-capture` binding), and rc-players' Compose Multiplatform creation API (the
  `livery-capture-cmp` binding) doesn't need Robolectric.

### 2. Find designs with KSP

A `livery-ksp` processor reads annotated composables and generates the `LiveryCatalogSource`:

```kotlin
@LiveryDesign(id = "kotlinconf-session-keynote", contract = "confetti/session@1", variant = "keynote")
@LiveryTarget("in-app", widthDp = 360, heightDp = 200)
@LiveryTag("session", "1148503")
@RemoteComposable @Composable
fun KotlinConfKeynoteSession() { … }
```

The design list can no longer drift from the functions. Duplicate ids, unknown target
profiles and malformed tags become processor errors with a source location.

### 3. Typed contract accessors, then maybe FIR checks

Generate a Kotlin type per contract from its JSON, with Gradle codegen or the same KSP processor.
Each type has its inputs, lists and actions as properties, plus the context-parameter DSL Confetti
wrote by hand (`text { title }`, `action { open }`, `listText(speakerList, i, name)`). Apps then
get compile-time checks for free.

Only after that, and only if apps hit errors the types can't express, add FIR checkers. At that
point it's a compiler plugin with one job and a small Kotlin-version surface.

## What changes for apps

| | Today | After step 1 | After step 2 | After step 3 |
| --- | --- | --- | --- | --- |
| Capture task | hand-written `JavaExec` or test filter | the plugin's | ← | ← |
| Design list | hand-written | hand-written, one interface | generated from annotations | ← |
| Contract checks | at capture (and Confetti's own DSL) | ← | ← | generated typed DSL, at compile time |

None of this changes the protocol, the catalog layout or `livery build`, and it puts nothing
app-specific in `livery-protocol` or `livery-client`.
