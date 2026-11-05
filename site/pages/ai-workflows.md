title: AI workflows
order: 23

# AI workflows

Livery doesn't assume one way of working with agents. Every workflow gets the same three things:
**the contract** (what a template may read and do), **the gate** (the contract check and the
publisher's validation) and **a render** to look at. Drafts are unsigned and play only in preview
channels; production catalogs are signed with a key the agent doesn't hold.

| Workflow | The agent produces | It reaches users by |
| --- | --- | --- |
| Repository + CI | template source or `.rc` in a pull request | the PR's preview channel, then `main` on merge |
| Live co-editing | drafts in a session with a person | the person accepting it |
| Campaign generation | data only: copy, images, placement rules | a reviewer approving the rule or decision |
| Runtime personalisation | data per request, inside a contract | your decision server, validated twice |
| On-device | a choice among installed designs, or data | at once; nothing new is downloaded |

## Repository + CI (start here)

1. The agent edits the catalog source in a branch: a design's `.rc`, its sidecar, a rule.
2. CI runs `livery build` (which refuses undeclared reads, bad examples and bad payloads) and
   publishes the branch to a `pr-<n>` channel.
3. Reviewers point a debug build at that channel and look at it with real data.
4. Merging publishes `main`, signed.

## Prefer data over documents

Generating copy and images for a reviewed template is safer, cheaper and easier to review than
generating a template. Give the agent the contract and its examples, ask for a dataset, and run
`ContractBinder.bind` on what comes back: over-long strings, unknown keys and wrong types are
refused before anyone sees them.

## Planned

- `livery-mcp`: `list_contracts`, `get_contract`, `validate_template`, `validate_data`,
  `render_preview`, `submit_draft`, `await_feedback`, `publish`, over any catalog.
- Live sessions over a relay (as Home Assistant's agent sessions work today), so a person and an
  agent edit together with the person's real data on screen.
