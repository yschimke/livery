# Players

Livery plays a template through a **player**. Today that is one: rc-players' Compose Multiplatform player
(`RcComposePlayer`, id `cmp-android` on Android). On Android there are two more worth supporting, both
AndroidX's own: the **View** player and the **embedded** (pure Compose) player. This document is what each one
offers, how Livery would plug them in, and how they are to be benchmarked. It is a design and a record of what was
checked, not a product page: nothing here is on the public site.

Facts below were read from the published `androidx.compose.remote` `1.0.0-alpha23` artifacts (the version the
repository already uses) and from rc-players' README. Anything not checked is marked **unverified**.

## The three players

| | Compose Multiplatform | AndroidX View | AndroidX embedded |
| --- | --- | --- | --- |
| rc-players id | `cmp-android` | `androidx-view` | `androidx-embedded` |
| Artifact | `ee.schimke.composeai:rc-player-compose` | `androidx.compose.remote:remote-player-view` | `androidx.compose.remote:remote-player-compose` (`embedded` package) |
| Entry point | `RcComposePlayer(prepared, …)` | `RemoteComposePlayer`, an Android `View` (a `FrameLayout`) | `RcPlayer(state \| CoreDocument, …)` composable; `ExperimentalRemoteDocumentPlayer` |
| How it draws | its own Compose-native interpreter, on every Compose target | paints to a framework `Canvas` inside a View | a pure-Compose interpreter: emits Compose layout and draw nodes |
| In a Compose tree | native | `AndroidView` (or AndroidX's `RemoteDocumentPlayer` composable, which does that) | native |
| Targets | Android, desktop, iOS, Wasm | Android | Android |
| Status | rc-players' supported product | AndroidX alpha | AndroidX alpha, `@Experimental` |

rc-players also vendors a pinned, patched copy of the embedded player (`third_party/rc-embedded-player`), marked
**testing only**. Livery should depend on AndroidX's published artifacts, not on that copy.

Only the CMP player gets rc-players' own work: the prepared-document cache with the `forDisplay` rewrite, deferred
layout animations and flat layout (3.3.0). The AndroidX players take a document and draw it.

## What Livery needs from a player

`LiveryTemplate` does more than call a player. What is the player's and what is Livery's:

| Need | Livery today (CMP) | View player | Embedded player |
| --- | --- | --- | --- |
| Document | bytes, prepared once per template and display (`RcPreparedDocumentCache`, background) | `setDocument(byte[] \| InputStream \| RemoteDocument)`, `updateDocument`; a `RemotePreparedDocument` platform class exists (**unverified** how to use it) | `RcPlayerState(CoreDocument, prefix)`; the `CoreDocument` comes from a `RemoteDocument(byte[])` |
| Values before the first frame | seeded into a state map, then pushed on change | `setUserLocalString/Int/Color/Float/Bitmap(name, v)`, `clearUserLocal*`; long through `StateUpdater.setNamedLong` | `RcPlayerState.floatState(name)` / `intState(name)` …, and a `StateUpdater` |
| Host actions (named) | `RcPlayerEvent.HostNamedAction(name, value)` | `player.document.document.addActionCallback { name, value -> }` (`CoreDocument.ActionCallback`), checked in the bytecode: `runNamedAction` ends there. `addIdActionListener` is the *id* form (`onAction(int, String)`), not Livery's | a named-action callback `(String, Any, StateUpdater)`, plus an id one `(Int, String)` |
| Images | `LiveryImageLoader`: `livery:value/<input>` URLs from the slot's bindings, then catalog assets, https, `app:` | `setBitmapLoader(BitmapLoader)`: `loadBitmap(url): InputStream`; `setUserLocalBitmap(name, bitmap)` | `RcImageLoader.loadImage(id: Int): State<Drawable>`; image ids, not URLs (**unverified** how a URL reaches it) |
| Fonts | `LiveryTypefaceLoader`, catalog fonts, system fallback | `setTypefaceResolver(TypefaceResolver)` | `EmbeddedPlayerTypefaceResolver`, `GmsFontLoader` (Google fonts) |
| Theme | `RcPlayerTheme` | `setTheme(THEME_LIGHT / DARK / UNSPECIFIED)` | **unverified** |
| Impressions, viewability, leave | Livery, around the player (`onVisibilityChanged`) | unchanged | unchanged |
| First frame complete | Livery prepares the document and fonts before a card composes | the document is parsed in `setDocument`; Livery must do it ahead | the same |
| Frame pacing | rc-players | `setMaxFps`, `setUseChoreographer`, `touchBoost` | Compose |

Everything in the last-but-two rows is already Livery's and would not change. Livery's rules still apply to any player:
rendering never waits on the network, a design that cannot play falls back to the automatic one, and a card after the
first of its template draws complete on its first frame.

## Proposed shape

One place decides the player, and one `LiveryTemplate` call site keeps everything else.

```kotlin
// livery-compose, commonMain: the seam
interface LiveryPlayer {
  val id: String
  /** What the player can draw, for template selection (surface, RC API level, op profiles). */
  val capabilities: PlayerCapabilities
  /** Gets what a card of [template] needs into memory before it composes: the document parsed, fonts loaded. Off the main thread. */
  suspend fun prepare(template: LoadedTemplate, resources: LiveryResources, display: RcDisplay)
  fun isPrepared(template: LoadedTemplate, display: RcDisplay): Boolean
  /** Draws the card. Values, actions and events are Livery's: the player is handed the values to show and reports named actions. */
  @Composable fun Play(request: PlayerRequest)
}
```

- `LiveryHost(…, player: LiveryPlayer = LivePlayers.Cmp)`: the default is today's behaviour, unchanged.
- `LiveryTemplate` keeps the tracked wrapper (visibility, impression, viewable, leave), value seeding, action
  dispatch through `ActionRouter` and the first-frame rule, and calls `host.player.Play(…)` where it now calls
  `RcComposePlayer`. `LiveryResources.readyToDraw` asks the player to `prepare`.
- A new Android-only module, **`livery-androidx-players`**, provides `LivePlayers.AndroidxView` and
  `LivePlayers.AndroidxEmbedded`, depending on the two AndroidX artifacts. The core and `livery-compose` gain no
  AndroidX dependency.
- **The action convention is shared, not copied.** `RcPlayerEvent.toTemplateAction` is rewritten over a function that
  takes a name and a value, which the new players call too (one gate: AGENTS.md).
- **Template selection** reads each player's `capabilities`, so a catalog does not hand a player a document it cannot
  draw: this must be checked per player, because AndroidX's alpha supports a different set of operations (**unverified**).

### Decisions to confirm

1. **Strategy on the host, not a composable per player.** One call site keeps the benchmarks and apps swapping a
   parameter; the cost is an interface in `livery-compose`'s core path.
2. **Published or not.** A new library module goes into the published list (AGENTS.md). The AndroidX players are alpha;
   a published module would pin them. Proposal: add the module, leave it out of `published` until the players are stable.
3. **Which "embedded".** AndroidX's own `remote-player-compose`, not rc-players' vendored copy and not rc-players'
   `RcEmbeddedPlayer` (live composition for previews, a different thing).

## Known gaps, up front

- No `forDisplay` rewrite, deferral or flat layout on the AndroidX players: Livery's optimisation lanes do not apply.
- Fonts and images go through different interfaces and need an adapter each; the embedded player's image path is by id.
- Document parsing happens inside the player, so "prepare off the main thread" means parsing a `RemoteDocument` ahead of
  time and handing it over, if the players accept that (**unverified** for the View player).
- Alpha APIs: a bump of `compose-remote` can break the adapters. Pin and test against rc-players' compare lanes.
- Pixels differ between players (rc-players' own compare lanes exist for that reason): the benchmark twins are held to
  the CMP player's pixels, so the other players need their own parity check before their numbers mean anything.

## Benchmarks

Same host, same cards, same device as the existing ones, with a lane per player and for each both **through Livery**
and **the player alone** (so Livery's overhead is separate from the player's cost):

| Lane | What |
| --- | --- |
| `livery` | CMP through Livery (today's default) |
| `rc` | the CMP player alone |
| `livery-view`, `view` | the View player through Livery / alone |
| `livery-embedded`, `embedded` | the embedded player through Livery / alone |
| `compose` | the Compose UI twin (the bar) |

Scenarios: cold start (the document host), data change, feed fling, and for the feed a **scroll-back** and a **mixed**
feed, since a `View` in a `LazyColumn` recycles differently from Compose nodes. Metrics are the existing ones plus
Livery's trace sections (`livery:*`, see [benchmarks/PLAN.md](benchmarks/PLAN.md)), the number of recompositions per
change and, per lane, a pixel check against the CMP render. Results go in `docs/benchmarks/PLAYERS.md`.

## Open questions

- How a `RemotePreparedDocument` is built and whether the View player takes it.
- The embedded player's image path (ids) against Livery's `livery:value/<input>` URLs.
- Which RC API levels and profiles each AndroidX player supports, for `PlayerCapabilities`.
- Theme on the embedded player.
- Accessibility: the View player ships an accessibility layer (`RemoteComposeTouchHelper`); the embedded and CMP
  players' semantics trees differ. Measure the node counts (planned in benchmarks/PLAN.md).
