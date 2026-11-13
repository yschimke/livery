title: Repository mode
order: 19

# Repository mode

**In one line:** run Livery from a GitHub repository alone. Every change to what your apps show
(a template, a design, a placement rule, an A/B test, a seasonal variant) is a pull request; CI
validates it and posts a review report; merging publishes it, signed. No server, no console.

## How it works

```
catalog/                 the source tree (see Getting started)
  catalog.json
  contracts/  designs/  placements/  experiments/  seasons/  themes/
.github/workflows/
  catalog-review.yml     on pull requests: build, inspect, compare with the base, draw, comment
  catalog-publish.yml    on main: build, sign, release
```

1. Someone (a person, or an agent with `livery-mcp`) edits the tree in a branch and opens a pull
   request.
2. **Review**: Livery's review workflow builds the tree, decodes every template, compares it with
   the pull request's base, and posts one comment (updated on every push) written to be read in
   ten seconds. Its first line is the verdict and what to do (`Compatible with 58a5852: nothing
   breaks for existing apps`, or `Not compatible with 58a5852: 2 problems to fix`). Then come only
   the things that need a decision: problems to fix, and anything this change newly does that is
   worth a look. A note that applies to many templates is said once, with the designs it applies to,
   and something the base already had (an optional value with no default, say) is not repeated when
   a template is only regenerated. Contract changes, designs, experiments and seasons, and placement
   rules that changed follow, with a table of the designs whose templates changed (size, and the
   data they read or send only when that changed). Every changed template is in a collapsed
   section. With `previews: true` the comment also shows each changed design drawn with its
   contract's examples, before and after. It fails when the change would break installed apps.

   ```text
   ### Compatible with `58a5852`: nothing breaks for existing apps

   5 designs, 181 templates: 145 changed, 36 unchanged

   **Worth a look**
   - `subtitle` is optional and its contract gives it no default: when an app leaves it out, the
     template draws its own built-in fallback. In 1 template: `tile-default`

   | Design | Templates | Size |
   |---|---|---|
   | `tile-default` | 1 changed | 7.8 KB (was 7.5 KB, +5%) |
   ```
3. Reviewers read the comment and the diff, and approve. Make the review job a required check, and
   put the tree under `CODEOWNERS` so the right people approve.
4. **Publish**: merging runs the publish workflow: it builds the tree again, signs the manifest
   with the key in a repository secret, and releases it. Apps pick it up on their next refresh.

Rolling back is a revert: the revert publishes like any change, with a higher serial, so apps take
it.

## Set up

Generate a signing key once, keep the private half as a secret and embed the public half in your
apps (see [Signing](signing.md)):

```bash
livery keygen acme
gh secret set LIVERY_SIGNING_KEY < acme.key
```

Then call Livery's reusable workflows, pinned to a release:

```yaml
# .github/workflows/catalog.yml
name: Catalog
on:
  pull_request: { paths: ['catalog/**'] }
  push: { branches: [main], paths: ['catalog/**'] }
jobs:
  review:
    if: github.event_name == 'pull_request'
    uses: yschimke/livery/.github/workflows/catalog-review.yml@v0.3.0
    with: { source: catalog, livery-version: 0.3.0 }
    permissions: { contents: read, pull-requests: write }
  publish:
    if: github.event_name == 'push'
    uses: yschimke/livery/.github/workflows/catalog-publish.yml@v0.3.0
    with: { source: catalog, livery-version: 0.3.0, key-id: acme-1, tag: catalog }
    secrets: { signing-key: '${{ secrets.LIVERY_SIGNING_KEY }}' }
    permissions: { contents: write }
```

| Input | Workflow | |
|---|---|---|
| `source` | both | the source tree, relative to the repository root |
| `source-artifact` | both | a source tree an earlier job uploaded, downloaded to `source`, for a catalog whose templates a build generates (captured from Kotlin): the review then compares it with an empty tree, since its base was never committed |
| `livery-version` | both | the Livery CLI release to run |
| `inspect` | both | decode every template (`--inspect`); default `true` |
| `comment` | review | post the report on the pull request; default `true` |
| `previews` | review | draw each changed design before and after with its contract's examples; default `false`. Needs `contents: write` (below) |
| `preview-size` | review | draw the templates covering this size, `<w>x<h>` in dp; default each design's largest |
| `key-id` | publish | the key id apps trust |
| `tag` | publish | a rolling release tag (below); empty for a new `catalog-<run>` release marked latest |

**Where it publishes.** With `tag`, every publish updates that one release: templates and images
are content-addressed, so only missing ones go up, beside the old ones, and the signature and
manifest last. Publishes run one at a time per release, and one never replaces a newer catalog
(a higher serial).
Apps read it with `GitHubReleaseSource(owner, repo, tag = "catalog")`, and the repository's
"latest" release stays the app's own, so the catalog can live in the app's repository. Without
`tag`, each publish is a new release marked latest, for a repository that holds only the catalog
(`GitHubReleaseSource(owner, repo)`). See [Hosting](hosting.md) for other hosts.

## A/B tests are pull requests

An experiment is a file; starting, growing and ending it are edits the review report explains:

```json
// catalog/experiments/tile_look.json
{ "id": "tile_look", "allocationBasisPoints": 1000,
  "startEpochMs": 1796083200000, "endEpochMs": 1798761600000,
  "arms": [ { "id": "control" }, { "id": "retro", "designs": { "live/status-tile@1": "tile-retro" } } ] }
```

- Raising `allocationBasisPoints` only adds users; the report says so.
- Lowering it, or changing arms or weights, is flagged for review: users already enrolled can move
  between arms, so analyse the result as a new test (or start one under a new id).
- Ending a test is setting `endEpochMs`, or deleting the file; the report lists who goes back to
  the usual designs.

How experiments assign and report is on [Analytics and experiments](analytics-and-experiments.md).

## Seasonal variants

A season swaps the design a contract's slots draw, for everyone, between two dates:

```json
// catalog/seasons/winter.json
{ "id": "winter_2026", "start": "2026-12-01", "end": "2027-01-07",
  "designs": { "live/status-tile@1": "tile-winter" } }
```

- `start` is inclusive and `end` exclusive: a date (midnight UTC) or an instant with its offset
  (`2026-12-01T00:00:00+13:00`). `conditions` (platforms, locales, segments) narrow it.
- The season's design is `Always` (downloaded with the catalog, so the day it starts needs no
  network) and `"automatic": false`, so ordinary selection never picks it, whatever else fits:
  only the season draws it, and pickers don't offer it. `livery build` refuses a season whose
  design isn't both, and two seasons over one contract at once. A catalog with such a design
  requires protocol level 6 (clients from Livery 0.3).

  ```json
  // catalog/designs/tile-winter/design.json
  { "id": "tile-winter", "contract": "live/status-tile@1", "title": "Winter",
    "install": "Always", "automatic": false }
  ```
- A season is written into the manifest as a one-arm experiment at full allocation, so any client
  that draws experiment design arms draws seasons (give the slot's planner an `Experiments`), and
  analytics see it as `exp_<id>`. Seasons come before the catalog's own experiments: a season
  replaces a design under test while it runs.
- Ship the season's design ahead of time: apps download `Always` designs with the catalog, so on
  the day it switches without the network.

## Pictures in the review

`previews: true` draws, for each design the change adds, changes or removes, one of its templates
with every example in its contract (up to four), before and after: the template covering
`preview-size`, or the one with the largest smallest size, drawn at that size. The data is the
contract's own examples, so the same picture means the same pixels: a design whose templates
changed but draw the same (an optimisation) shows one row marked unchanged. Placeholders and
documents that don't decode are listed as not drawn.

The comment shows images from GitHub, so the workflow commits them, alone, to a branch of their
own, `livery-previews/pr-<number>`, replaced on every push, and cites that commit. That needs
`contents: write`:

```yaml
  review:
    uses: yschimke/livery/.github/workflows/catalog-review.yml@v0.3.0
    with: { source: catalog, livery-version: 0.3.0, previews: true }
    permissions: { contents: write, pull-requests: write }
```

Without it (or from a fork) the comment names the files instead, and the run's `livery-previews`
artifact has them. Delete the branches of closed pull requests whenever you like.

## Older preview data

A contract's `examples` are the data every template is previewed, reviewed and verified with. When a
change edits or drops one, the old data doesn't stop mattering: an installed app may still send data
shaped like it. So it is kept, beside the contract, and every template stays checked with it:

```
contracts/
  status-tile.json
  examples/
    status-tile/
      on-1a2b3c4d.json     the data `on` had before, named by its hash
```

`livery check` fails while the base has example data that is neither in the contract (under any
name) nor kept, and when a kept example no longer binds to the contract. Keep it with:

```bash
livery keep-examples catalog --against origin/main
```

Review pictures draw each kept example after the current ones, labelled `on (kept 1a2b3c4d)`, and
`livery-verify` plays every launcher widget with them too. The catalog build ignores the folder:
kept examples are for checking, not for apps.

## Playing a template as a release played it

`livery play` plays one template with one set of data as that release of Livery plays it, and
writes a PNG: on rc-players' Compose player (`--surface compose`), or written for a widget and
played on Android 16's own launcher widget player (`--surface launcher-widget`). It exits 1 when the
template crashes or is refused, which is what a home screen shows as a warning sign. The widget is
published as a widget provider publishes it, through rc-players, which first recovers what the
platform player can't read; `--as-is` hands the player the document without that, to find a
template that only plays because it is recovered.

```bash
livery play designs/tile-default/launcher.rc --contract contracts/status-tile.json \
  --example on --surface launcher-widget --size 352x190 --out tile.png
```

Every Livery release ships it, so to try a template as an app on an older release plays it,
download that release's CLI and run the same command:

```bash
v=0.3.0
curl -fsSL -o livery-$v.zip https://github.com/yschimke/livery/releases/download/v$v/livery-$v.zip
unzip -q livery-$v.zip && livery-$v/bin/livery play …
```

`--example` takes a contract example or, with `--catalog <src>`, a kept one (`on (kept 1a2b3c4d)`).
The widget player runs under Robolectric in a Java 21 JVM (`LIVERY_JAVA`, else `JAVA_HOME`); on
first use it downloads Android 16 and Robolectric's native graphics from Maven Central, checked
against their published SHA-1, into `LIVERY_CACHE` (default `~/.cache/livery`).

## Review tools

| Tool | |
|---|---|
| `livery check <src> --against origin/main --inspect` | the review report in a terminal; `--markdown` for a comment, `--json` for other tools; `--deny-new-features` fails when a template gains a risky feature |
| `livery review <src> --against origin/main --previews <dir>` | the markdown report with pictures: each changed design drawn with its contract's examples, before and after, as PNGs in `<dir>` (`--image-base <url>` for where they will be served, `--size <w>x<h>` to choose the template) |
| `livery build <src> <out> --inspect` | everything the publish would refuse |
| `livery keep-examples <src> --against origin/main` | keeps the example data the base had that the tree no longer has (above) |
| `livery play <template.rc> --contract <c> --example <e> --surface launcher-widget [--as-is]` | one template, played as this release plays it: a PNG, and exit 1 on a crash (above) |
| `livery-mcp` | the same checks and `render_preview` for agents, with `list_history` and previews at a revision (see [AI workflows](ai-workflows.md)) |

## Planned

- Preview channels per pull request, for a debug build to play before merging.
- Which Livery release each app release ships, recorded by the Gradle plugin, so a review can play
  every changed template as the app releases still in use play it (`livery play` from each
  release, for the app's last six months or each minor version).
