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
  catalog-review.yml     on pull requests: build, inspect, compare with the base, comment
  catalog-publish.yml    on main: build, sign, release
```

1. Someone (a person, or an agent with `livery-mcp`) edits the tree in a branch and opens a pull
   request.
2. **Review**: Livery's review workflow builds the tree, decodes every template, compares it with
   the pull request's base, and posts one comment (updated on every push) with what the change
   does: contract changes, templates added or changed (reads, emits, minor, size), designs
   (install policy, revocation), experiments and seasons (schedule, allocation, arms) and
   placement rules (every field that changed). It fails when the change would break installed
   apps.
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
| `livery-version` | both | the Livery CLI release to run |
| `inspect` | both | decode every template (`--inspect`); default `true` |
| `comment` | review | post the report on the pull request; default `true` |
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
- The season's design must be `Always` (shipped to everyone), or it would show only to people who
  installed it, and have its own `variant` (`"variant": "winter"`), with a default design for
  every target it has, so outside the season automatic selection keeps drawing the usual design.
  `livery build` refuses otherwise, and refuses two seasons over one contract at once.
- A season is written into the manifest as a one-arm experiment at full allocation, so any client
  that draws experiment design arms draws seasons (give the slot's planner an `Experiments`), and
  analytics see it as `exp_<id>`. Seasons come before the catalog's own experiments: a season
  replaces a design under test while it runs.
- Ship the season's design ahead of time: apps download `Always` designs with the catalog, so on
  the day it switches without the network.

## Review tools

| Tool | |
|---|---|
| `livery check <src> --against origin/main --inspect` | the review report in a terminal; `--markdown` for a comment, `--json` for other tools |
| `livery build <src> <out> --inspect` | everything the publish would refuse |
| `livery-mcp` | the same checks and `render_preview` for agents, with `list_history` and previews at a revision (see [AI workflows](ai-workflows.md)) |

## Planned

- Pictures in the review comment: each changed template rendered before and after with the
  contract's examples.
- Preview channels per pull request, for a debug build to play before merging.
