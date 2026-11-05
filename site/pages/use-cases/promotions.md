title: Promotions and growth
order: 11

# Promotions and growth

**In one line:** ship placement rules in the catalog (schedule, audience, rollout, frequency cap)
and the app chooses the promotion on the device, offline, with no decision server.

## How it fits

Rules live in the signed manifest, so targeting data never leaves the device and a promotion works
on a plane. A remote decider can still run first; local rules are the fallback.

## 1. A rule

`placements/spring-trial.json` in the catalog source:

```json
{
  "id": "spring-trial", "placement": "home.top", "priority": 10,
  "template": { "designId": "banner-classic" },
  "data": { "headline": "Spring sale: 50% off", "cta": "Upgrade" },
  "startEpochMs": 1790000000000, "endEpochMs": 1800000000000,
  "conditions": { "platforms": ["android", "ios"], "excludeSegments": ["subscriber"] },
  "rollout": { "experiment": "spring-2027", "arm": "banner", "toBasisPoints": 5000 },
  "frequency": { "maxImpressions": 3, "perSeconds": 86400 }
}
```

`livery build` checks `data` against the design's contract, so a too-long call to action fails the
build, not the app.

- **Rollout** is sticky: FNV-1a of `experiment:bucketingId` modulo 10000, the same on every platform.
- **Priority** picks between eligible rules; ties go to the lower id.

## 2. Decide and draw

```kotlin
val decider = FirstDecider(listOf(myRemoteDecider, LocalRulesDecider({ repository.manifest() }, frequencyStore)))
val context = DecisionContext(nowEpochMs = clock(), platform = "android", locale = "en-GB",
  appVersion = BuildConfig.VERSION_CODE.toLong(), bucketingId = installId, segments = userSegments)

// The key is everything the decision reads from the app: new segments (a new subscriber)
// re-decide. A refreshed catalog re-decides on its own. Add a time bucket if rules have schedules
// that should flip while the screen stays open.
LiverySlot(key = "home.top" to userSegments, host = host) {
  decider.decide("home.top", context)?.let { planner.forDecision(bannerQuery, it) } ?: PlanResult.Fallback("none")
}
```

Persist the `FrequencyStore` (the in-memory one resets per run) and record impressions with
`LocalRulesDecider.recordImpression`.

## 3. Ship a change

Edit the rule, rebuild the catalog with a higher `serial`, publish. Apps pick it up at their next
refresh (`ttlSeconds`, default an hour); nothing waits for an app release.

## Deployment checklist

- [ ] Rules in `placements/`, built and signed with the catalog.
- [ ] A stable, resettable `bucketingId` (an install id, not a user id).
- [ ] A persisted `FrequencyStore`.
- [ ] Segments computed on the device only.
- [ ] A staging channel to try a rule before `main` ([Hosting](../hosting.md#channels)).
