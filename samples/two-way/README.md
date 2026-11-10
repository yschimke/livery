# Two-way sample catalog

Templates whose own controls change their inputs: `set` actions (PROTOCOL.md §7.1). It is its own
catalog because a `set` makes a catalog need protocol level 7, while `samples/catalog` stays the
level-2 baseline the publisher's level tests build on.

- `shop/quantity@1`, design `quantity-stepper`: a quantity with `−` and `+`, each a `set` of
  `quantity` with the value the document computes when tapped. Captured from Compose by
  `livery-capture`'s `SampleQuantityTest`. Its `.rc` and sidecar are build output, never
  committed: `./gradlew :livery-capture:sampleCatalogs` lays this tree out with them in
  `livery-capture/build/samples/two-way`.

```sh
./gradlew :livery-capture:sampleCatalogs
livery build livery-capture/build/samples/two-way build/two-way
```
