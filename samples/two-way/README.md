# Two-way sample catalog

Templates whose own controls change their inputs: `set` actions (PROTOCOL.md §7.1). It is its own
catalog because a `set` makes a catalog need protocol level 7, while `samples/catalog` stays the
level-2 baseline the publisher's level tests build on.

- `shop/quantity@1`, design `quantity-stepper`: a quantity with `−` and `+`, each a `set` of
  `quantity` with the value the document computes when tapped. Captured from Compose by
  `livery-capture`'s `SampleQuantityTest`.

```sh
livery build samples/two-way build/two-way
```
