title: Analytics and experiments
order: 22

# Analytics and experiments

**In one line:** send Livery's events to any analytics pipeline, run A/B tests over designs and
placements from the catalog, and keep frequency caps across a user's devices. Firebase is built
in (`livery-firebase`); anything else plugs in through an interface.

## Analytics

Every slot reports `TemplateEvent`s: `Impression`, `Viewable`, `Action`, `Dismiss`, `Fallback`,
`Error` and `Exposure`, each with its placement, decision, design, template and the experiment
arms that shaped it. `AnalyticsEventSink` turns them into flat events (`livery_impression`,
`livery_action`, … with `placement`, `design_id`, `template`, `action`, `exp_<experiment>`
parameters) that fit Firebase's and most pipelines' limits, and hands them to every
`AnalyticsSink` you give it:

```kotlin
val events = AnalyticsEventSink(
  FirebaseAnalyticsSink(Firebase.analytics),            // livery-firebase
  AnalyticsSink { name, params -> amplitude.track(name, params) },  // or Segment, your warehouse, …
)
val planner = SlotPlanner(repository, events, clock)   // with experiments: see below
```

A pipeline that throws doesn't stop the others. To also keep Livery's raw events (a debug log, a
test probe), combine sinks with `eventSinks(events, EventSink { log(it) })`.

## A/B tests

Experiments live in the catalog, in `experiments/<any>.json`, and ship in the manifest:

```json
{ "id": "tile_look", "allocationBasisPoints": 5000,
  "conditions": { "platforms": ["android"] },
  "arms": [
    { "id": "control" },
    { "id": "retro", "designs": { "live/status-tile@1": "tile-retro" } }
  ] }
```

- An experiment's `id` is lowercase letters, digits and `_` (at most 20): it becomes the
  `exp_<id>` analytics parameter and the `livery_exp_<id>` Remote Config key as it is.
- Users who match the conditions during the schedule are enrolled at `allocationBasisPoints`
  (5000: half of them), each in one sticky arm in proportion to the arms' `weight`s. Raising the
  allocation only adds users; nobody changes arm.
- An arm's `designs` pick the design a contract's slots draw when the app didn't choose one. Here,
  enrolled users in `retro` see the retro tile everywhere it has a template.
- A placement rule can be limited to an arm, to test copy, timing or a different creative:
  `"arm": { "experiment": "promo_copy", "arm": "b" }`. A catalog with such a rule requires
  protocol level 4, so older clients, which would show it to everyone, keep their previous
  catalog. Design arms don't: an older client simply draws what the control arm does.
- The first time a template an arm chose actually draws (its `Impression`), Livery emits one
  `Exposure` event, for design arms and arm-targeted placements alike. Analyse exposures, not
  assignments: a user assigned to an arm they never saw isn't in the test.

```kotlin
val experiments = Experiments(
  manifest = { repository.manifest() },
  context = { DecisionContext(now, "android", locale, appVersion, bucketingId, segments) },
  assigner = FirstAssigner(RemoteConfigAssigner(Firebase.remoteConfig), LocalBucketing),
  events = events,
)
// Slots report through experiments.sink: every event passes on, and an arm's first impression
// adds its exposure. A widget, which reports no impressions, calls experiments.exposed(…) itself.
SlotPlanner(repository, experiments.sink, clock, experiments)
LocalRulesDecider({ repository.manifest() }, caps, experiments = LocalBucketing)
```

**Assignment is local by default** (`LocalBucketing`): no service, offline, the same arm on
Android, iOS and the server for the same bucketing id. To run experiments from Firebase A/B
Testing or your own service, implement `ExperimentAssigner`. `RemoteConfigAssigner` reads the
Remote Config parameter `livery_exp_<experiment>`, which holds the arm id. An assigner answers
only from what's on the device. Call Remote Config's `fetchAndActivate()` at app start as usual;
until it has an answer, the next assigner in the `FirstAssigner` does.

## Frequency caps across devices

A placement rule's `frequency` (`{ "maxImpressions": 2, "perSeconds": 604800 }`: twice a week)
counts impressions on the device. `KeyValueFrequencyStore` keeps that count across restarts. To
cap per user rather than per device, use `SyncedFrequencyStore` with a `FrequencyBackend`.
Firestore is built in, and anything with "push records, pull records since" fits:

```kotlin
val caps = SyncedFrequencyStore(kv, FirestoreFrequencyBackend(Firebase.firestore, user.uid), deviceId, clock)
val decider = LocalRulesDecider({ repository.manifest() }, caps, experiments = assigner)
caps.sync()   // at app start, on a schedule, after a placement draws
```

Decisions read only the local count, so a slot never waits on the network. Impressions queue
while offline and go up on the next `sync()`, and each one is counted once however often it syncs.
A cap is at most one sync out of date. With Firestore, store impressions under
`livery/<userId>/impressions` and write security rules so each user reads and writes only their own.

## Deployment checklist

- [ ] One `AnalyticsEventSink` shared by every slot, planner and action router.
- [ ] A stable, resettable `bucketingId` per install (not an advertising id), kept across launches.
- [ ] Experiments reviewed in the catalog like any change; `livery build` refuses unknown designs,
      arms and rules for arms that don't exist.
- [ ] `fetchAndActivate()` at start when Remote Config assigns arms.
- [ ] `caps.sync()` at start and after impressions when caps are shared; Firestore rules per user.

## Planned

- The Swift package decodes experiments, assigns arms (`LocalBucketing`, the same arms as Kotlin by
  the conformance fixture) and applies arms to placement rules. Design arms in its planner,
  analytics sinks and synced frequency caps are still to come.
