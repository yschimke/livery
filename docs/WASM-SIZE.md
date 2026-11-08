# Web Wasm size

Measured on the merged 0.6.0 sources before adding persistent caching, with Kotlin 2.4.21 and
its pinned Binaryen toolchain (`optimizeWebWasm`). These are raw Wasm file sizes, not transfer
sizes; the browser suite uses the optimized distribution with the same assertions.

| Build | Bytes |
| --- | ---: |
| `compileProductionLibraryKotlinWasmJs` | 4,749,595 |
| Binaryen production optimization | 1,454,082 |
| Saved | 3,295,513 (69.4%) |

`webDist` copies the JavaScript glue from the compiler and the Wasm from `optimizeWebWasm`.
It does not modify the compiler's output in place. Both tasks are dependencies of the distribution,
so npm packaging and CI use the same optimized module.

Reproduce with `build-brief ./gradlew :livery-web:webDist` and inspect both files with:

```sh
python3 scripts/wasm-size.py \
  livery-web/build/compileSync/wasmJs/main/productionLibrary/kotlin/livery-livery-web.wasm \
  livery-web/build/optimized-web/livery-livery-web.wasm
```

The unoptimized module's `name` section alone is 2,218,445 bytes (46.7% of the file). Its code
section is 1,697,162 bytes. Named function-body attribution before optimization:

| Group | Body bytes |
| --- | ---: |
| Livery, including its generated serializers | 333,274 |
| RC protocol/player | 331,421 |
| Kotlin runtime/stdlib | 296,181 |
| Ktor | 258,173 |
| Other/unnamed | 256,253 |
| kotlinx-serialization | 155,463 |

These are function bodies grouped by their debug names, not estimates of independently removable
bytes. Generated serializers also occur in their owning modules. Binaryen combines, inlines and
eliminates functions and strips names, so attributing the optimized output by package would be
misleading. The measurement script reports sections and leaves unnamed functions unattributed.

Ktor is a candidate for a later reduction, but this change retains it. A browser `fetch` adapter
must preserve bounded streaming reads and the redirect policy in `RedirectingFetcher`. Manual
cross-origin redirects expose opaque responses, so replacing Ktor with an automatic-follow fetch
would silently bypass the image host gate. That needs a separate design and parity tests.
