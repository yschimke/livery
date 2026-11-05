title: AI workflows
order: 23

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

The server never signs anything. A failed draft is never left in the tree.

## 2. Repository + CI (start here)

1. The agent works in a branch with the server above: it reads the contract, validates and renders
   its document, and calls `submit_draft`.
2. CI runs `livery build --inspect`. That refuses undeclared reads, bad examples and bad payloads,
   and decodes every `.rc` to check it against its sidecar. CI then publishes the branch to a
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
- [ ] CI runs `livery build --inspect`, then signs with a key the agent can't read.
- [ ] Preview channels for review, never the production channel.

## Planned

- Live sessions over a relay, with `livery-server` (as Home Assistant's agent sessions work today):
  `await_feedback`, `send_message` and `set_status`, so a person and an agent edit together with
  the person's real data on screen.
- `compile_rc_json`: RC authoring JSON to `.rc`, for agents that write documents directly.
