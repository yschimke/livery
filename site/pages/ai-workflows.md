title: AI workflows
order: 24

# AI workflows

**In one line:** point any agent at `livery-mcp <catalog>`, and it gets the contract, the gate
and a render, but never the signing key.

Livery doesn't assume one way of working with agents. Every workflow gets the same three things:
**the contract** (what a template may read and do), **the gate** (the contract check and the
publisher's validation) and **a render** to look at. Drafts are unsigned and play only in preview
channels. Production catalogs are signed with a key the agent doesn't hold.

| Workflow | The agent produces | It reaches users by |
| --- | --- | --- |
| Repository + CI | template source or `.rc` in a pull request | the PR's preview channel, then `main` on merge |
| Campaign generation | data only: copy, images, placement rules | a reviewer approving the rule or decision |
| Runtime personalisation | data per request, inside a contract | your decision server, validated twice |
| On-device | a choice among installed designs, or data | at once; nothing new is downloaded |
| Live co-editing *(planned)* | drafts in a session with a person | the person accepting it |

## 1. Give the agent the tools

`livery-mcp` is a Model Context Protocol server over stdio. It works on a catalog source tree, the
same one `livery build` reads.

```sh
./gradlew :livery-mcp:installDist
livery-mcp/build/install/livery-mcp/bin/livery-mcp path/to/catalog            # read-only
livery-mcp/build/install/livery-mcp/bin/livery-mcp path/to/catalog --write \
  --preview-out build/previews                                                # drafts and previews
```

Add it to an agent like any stdio MCP server. In Claude Code:

```sh
claude mcp add livery -- /abs/path/livery-mcp path/to/catalog --write --preview-out build/previews
```

| Tool | What it does |
| --- | --- |
| `list_contracts`, `get_contract` | what each contract lets a template read and emit, with its examples |
| `list_designs` | the designs for a contract, with each template's target and declared reads and emits |
| `validate_data` | binds data the way every client does (`ContractBinder`): the values it would play, or every problem |
| `validate_template` | decodes a `.rc` and reads its own named values and actions, checked against the contract; returns the sidecar to submit |
| `render_preview` | a PNG of a template with an example or the agent's data, at a size and density, light or dark |
| `submit_draft` (`--write`) | writes a template into the tree only if the document matches its sidecar and the whole catalog still builds |
| `build_preview` (`--preview-out`) | an unsigned catalog for a preview channel; the catalog's own channel is refused |
| `publish_preview` (`--preview-out`) | one document on its own, as an unsigned catalog of one design, checked against its contract |
| `list_history` | a design's templates' versions from the catalog's git log; `render_preview` with `revision` draws one |
| `check_compatibility` | the catalog against a git revision (default `HEAD`) or another tree, as `livery check` does: contract changes marked compatible or breaking, and per template what it reads and emits, the minor it requires and which values only its own default covers |
| `ui_builder_export` (`--ui-builder`) | a Compose UI Builder design at a revision, as a document with its `source` |
| `ui_builder_scaffold` (`--ui-builder`) | a contract as UI Builder host inputs, to add to a design before laying it out |

The server never signs anything. A failed draft is never left in the tree.

## Just a preview

Not every document has to join the catalog to be looked at. `publish_preview` (or
`livery preview <doc.rc> <out> --src <catalog> --contract <id@major>`) writes one document as a
catalog of its own, on a channel named after its hash (`preview-<hash>`). Point a debug build's
repository at it, or serve the directory. It is checked against the contract like any template,
and it expires in a week.

## Versions

Every template entry can say where it came from: its sidecar's `source` (`tool`, `design`,
`revision`, `commit`) is copied into the manifest. In the repository workflow, git is the history.
`list_history` shows the commits that changed each template, and `render_preview` with
`revision=<commit>` draws it as it was then, so an agent or reviewer can compare versions before
changing one.

## Compose UI Builder

Start the server with `--ui-builder https://<ui builder host>` and `UI_BUILDER_TOKEN` set
(module `livery-uibuilder`). `ui_builder_scaffold` gives the agent a contract as UI Builder host
inputs to add to the design first, so it reads Livery's names. `ui_builder_export` returns a design's document at a revision, or its
latest, with `source: {tool: ui-builder, design, revision}`. From there it is a document like any
other: `validate_template`, `render_preview`, then `submit_draft` with that `source` in the
sidecar, or `publish_preview`. To move a template to a newer UI Builder revision, export that
revision and submit it again; `list_history` shows each step. Without an agent, the same flow is
`livery ui-builder scaffold` and `livery ui-builder import` (see [Authoring templates](authoring.md)).

## 2. Repository + CI (start here)

1. The agent works in a branch with the server above: it reads the contract, validates and renders
   its document, and calls `submit_draft`.
2. CI runs `livery build --inspect` and `livery check --against origin/main`. The build refuses
   undeclared reads, bad examples and bad payloads, and decodes every `.rc` to check it against its
   sidecar and its contract's types; the check refuses a contract change apps already installed
   would break, and lists what the branch changes for review. CI then publishes the branch to a
   `pr-<n>` channel.
3. Reviewers point a debug build at that channel and look at it with real data.
4. Merging publishes `main`, signed by CI.

## Prefer data over documents

Generating copy and images for a reviewed template is safer, cheaper and easier to review than
generating a template. Give the agent the contract and its examples, ask for a dataset, and check
it with `validate_data` (in your own server, `ContractBinder.bind`). Over-long strings, unknown keys
and wrong types are refused before anyone sees them. `render_preview` with that data shows the
result on every template of the design.

## Deployment checklist

- [ ] `livery-mcp` in the agent's tools, pointed at the catalog source in a working branch.
- [ ] `--write` only where the agent works in a branch; read-only everywhere else.
- [ ] CI runs `livery build --inspect` and `livery check --against origin/main`, then signs with a
      key the agent can't read.
- [ ] Preview channels for review, never the production channel.

## Planned

- Live sessions over a relay, with `livery-server` (as Home Assistant's agent sessions work today):
  `await_feedback`, `send_message` and `set_status`, so a person and an agent edit together with
  the person's real data on screen.
- `compile_rc_json`: RC authoring JSON to `.rc`, for agents that write documents directly.
- Pictures in `check_compatibility`: each changed design rendered before and after with every
  example, as `livery review` does for a pull request.
- `livery check --against <release URL>`: a published catalog as the baseline, not only a git
  revision or a source tree.
- Retired versions in the manifest, so a decision or feed item pinned to an older template's hash
  keeps playing after the design moves on.
