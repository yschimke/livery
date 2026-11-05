title: Interactive ads
order: 10

# Interactive ads

**In one line:** your ad server answers a placement with a template reference and the ad's data;
Livery draws exactly that creative with that data, or nothing at all.

## How it fits

- The **template** is the creative's layout, published and signed in a catalog (by you, or by an
  ad partner whose key may sign only `ads/` contracts).
- The **decision** comes from your ad server at request time: which template (by design or exact
  blob), the data (copy, image URLs), tracking URLs and experiment arms.
- The client **never substitutes** another template for a decision, and checks the data against
  the contract before drawing.

## 1. The contract

```json
{
  "id": "ads/banner", "major": 1, "dataSource": "Decision",
  "inputs": {
    "headline": { "type": "string", "required": true, "maxLength": 40 },
    "cta":      { "type": "string", "required": true, "maxLength": 18 },
    "hero":     { "type": "image", "required": true }
  },
  "actions": { "click": { "kind": "open-url", "args": { "url": { "type": "string", "required": true } } } }
}
```

## 2. Publish the creatives

Each creative is a design (`ads-hero-v3`) with a template per surface you serve. Mark them
`"install": "OnDemand"`, so they download when first decided, not with every refresh.

If a partner publishes creatives, give them their own key and trust it only for `ads/`:

```kotlin
TrustedKey("partner-1", partnerPub, contractPrefixes = setOf("ads/"))
```

## 3. The decision your server returns

```json
{
  "placement": "feed.inline-1",
  "decisionId": "d-7f3a",
  "template": { "designId": "ads-hero-v3" },
  "data": { "headline": "Winter sale", "cta": "Shop now", "hero": "https://cdn.adnet.example/w.jpg" },
  "tracking": { "impression": ["https://t.adnet.example/i?d=7f3a"], "click": ["https://t.adnet.example/c?d=7f3a"] }
}
```

Pin `"sha256"` instead of `designId` to serve one exact blob. Validate `data` on the server with the
same contract before answering.

## 4. Draw it

```kotlin
val decision = adServer.decide("feed.inline-1")                // your call; returns a Decision
LiverySlot(key = decision?.decisionId, host = host, fallback = { /* nothing */ }) {
  if (decision == null) PlanResult.Fallback("no fill")
  else planner.forDecision(TemplateQuery("ads/banner@1", inApp, widthDp = 360), decision)
}
```

`Impression` fires after the first frame and `Viewable` after an unbroken second at least half on
screen; route them, and the decision's tracking URLs, through your `EventSink`.

## 5. Images

Ad images are remote. Serve them through an image proxy declared in the catalog, so the device talks
to one host you trust, never the advertiser's. See [Images and fonts](../images-and-fonts.md).

## Deployment checklist

- [ ] A partner key scoped to `ads/` contracts, if partners publish.
- [ ] Creatives `OnDemand`; the decision names a design or an exact `sha256`.
- [ ] Server-side contract validation of `data` before answering.
- [ ] `imageProxy` in the catalog, with `sourceHosts` limited to the ad CDNs.
- [ ] `open-url` allowlist in the `ActionRouter` covering click-through hosts.
- [ ] Impression and viewability events wired to your measurement.
