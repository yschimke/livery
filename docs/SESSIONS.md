# Agent sessions and UI Builder flows

Status: **in progress.** `livery-sessions-core` (the relay, links and the transcript) and
`livery-sessions-server` (routes, MCP tools, the save file and `SessionKind`) and Livery's own
`template` kind (`livery-sessions-templates`) are built; the other modules below are *Planned*. These are optional modules: nothing in `livery-protocol`,
`livery-client` or `livery-compose` depends on them.

homeassistant-remotecompose built this loop and runs it in production
([its agent-sessions design](https://github.com/yschimke/homeassistant-remotecompose/blob/main/docs/architecture/agent-sessions.md),
and the migration plan's track A). Confetti needs the same loop for its session and speaker
cards. The design below keeps what that app learned and leaves its concepts out, as AGENTS.md
requires.

## The loop

A person and an AI agent edit one thing together:
- the agent drafts;
- the person sees each draft drawn as it will be, with their own data;
- they talk;
- the person accepts or rejects.

An agent never ships anything itself.

```
Agent ──MCP──▶ session server ◀──HTTPS long-poll── person's app ──▶ the app's own backend
               links, codes, presence,              draws drafts,      applies an accepted draft
               conversation, drafts, gate           comments, decides
```

| Constraint | Rule |
| --- | --- |
| An agent often can't reach the app's backend (local installs, proxies) | Both sides connect **out**; nothing depends on an inbound connection |
| Backend credentials are rarely scoped | No agent holds one. It submits drafts; a person applies them |
| Apps go to the background and browser tabs reload | The person's side resumes from stored capabilities |
| Proxies cut long requests and buffer server-sent events | Long-polls of at most 25 s; no SSE, no WebSockets |
| MCP is request/response | The agent waits for the person with a long-polling tool (`await_feedback`) |

## Model

- **Link.** A lasting pairing between a person and an agent. It lasts 30 days after either side
  last used it, or until revoked. It holds no credential and grants nothing by itself.
- **Code.** A single-use code (31 symbols, no `0/O/1/I/L`, 8 characters) that lasts 15 minutes. It
  is redeemed by `connect`, or the reverse way through `request_access`. Failed redemptions share
  one budget: after 30 in a minute, every redemption waits.
- **Session.** One scope of one **kind**, owned by a person, with:
  - a baseline and a `revision`;
  - drafts;
  - a conversation;
  - presence (the agent's status and last call);
  - a decision.

  A session ends an hour after its last activity, a day after it started, or on revocation.
  Capabilities are per session: an owner capability and an agent capability.
- **Kind.** What a session edits.
  - `template` is Livery's own: a document for a contract and target.
  - An app registers its other kinds, for example a screen layout or an app's own config. Each
    kind is a **`DraftGate<T>`**: it checks a draft against the session's scope and says why it
    refuses.
- **Conversation.** Messages, comments, decisions and the agent's **steps** share one sequence,
  so the agent's `since` says what it has read.
  - A **step** (kind `tool`) is a tool the agent called: the tool, what it asked and what it got,
    **in words** rather than raw JSON, and whether it worked. Text is clipped to 500 characters and
    the newest 100 steps are kept. Steps are for the person to watch the agent work: an agent's own
    view (`get_session`, `await_feedback`) and a built-in agent's model context leave them out.
  - A **transcript** groups the agent's steps up to its next message into one **turn**, with that
    message as the reply; trailing steps are the turn in progress (`transcript`).
  - A comment may carry a **target**: an id in the draft plus a label. The label comes from the
    app's `TargetLabeler`, for example "Living room temperature (view 1, section 2, card 2)".
  - Comments left before an agent joins wait. The agent's first read tells it to start with them.
- **Render.** The person's app shares a PNG of the draft as it drew it, so the agent sees what the
  person sees. The server keeps only the newest per session, in memory.

## Wire format

The format is normative once accepted, with conformance fixtures under `conformance/sessions/`.
Every implementation runs them, including ports outside this repository, such as an app's own
backend that serves the same sessions directly.

**Agent: MCP, stateless JSON-RPC.** Every tool after joining takes `session`.

| Tool | Does |
| --- | --- |
| `connect(code, agentName?)` | Redeem a code: a setup code gives a `link`, a session code gives its `session` |
| `request_access(agentName?)`, `poll_access(requestToken)` | Ask first; the person enters the code |
| `await_request(link, since)` | Wait up to 25 s for the person's requests |
| `get_session(session)` | Scope, baseline, `revision`, conversation, `cursor`, and `next` (what to do first) |
| `set_status`, `send_message` | Presence, and a message in the person's panel |
| `report_step(session, tool, arguments, output, ok)` | A step in the person's chat; a built-in agent reports each of its tool calls |
| `submit_draft(session, revision, draft, summary?)` | Checked by the kind's gate |
| `put_asset(session, key, bytes)` | Images a draft needs |
| `await_feedback(session, since)` | Wait up to 25 s for comments or a decision |
| `get_render(session)` | The person's latest render, as MCP image content |

**Person: HTTPS, owner capability as bearer.**

- `POST links`; `GET link?since=`; `POST link/code`, `link/claim`; `DELETE link`.
- `POST sessions` (kind, scope, baseline, optional `link`); `GET session?since=` (held up to 25 s).
- `POST session/comment`, `session/decision`, `session/render`; `DELETE session`.

## Modules

| Module | Platform | Does |
| --- | --- | --- |
| `livery-sessions-core` *(built)* | KMP, transport-free | `SessionRelay` and `SessionLinks` with an injected clock and `Entropy` (`SecureEntropy` on the JVM): codes and the shared failed-code budget, links, presence, the conversation with steps, renders, long-polls, and `snapshot`/`restore` for a restart. Each kind's drafts sit behind a `DraftStore`, which also decides how long a session lives; `transcript` and `TargetLabeler` for the panel. Still to add: `DraftGate` for Livery's own `template` kind, and file persistence on the JVM |
| `livery-sessions-server` *(built)* | JVM, Ktor | `SessionServer` over the relay with the app's `SessionKind`s, and `SessionText` for what the agent is told in the app's words. `sessionRoutes` mounts both route sets under a prefix (`/api/agent` by default): the person's, long-polled and open to any origin, and the agent's MCP endpoint and `join`, refused to browser pages. The MCP endpoint speaks JSON-RPC itself, apart from `livery-mcp-core`'s catalog tools. `SessionFile` saves the relay and each kind's own drafts across a restart. A **`SessionKind`** is the extension point: its `DraftStore`, `read` for `get_session`, `submit` (the kind's gate), tools of its own (looked up on the session's own kind), an HTTP path, and its save. Kinds may share a tool's name only with the same description and schema (checked when the server starts) |
| `livery-sessions-templates` *(built)* | JVM | `TemplateKind`, Livery's own kind: an agent drafts a Remote Compose template for one contract and target, as `documentBase64`. Its gate is PROTOCOL.md §3.5's for unsigned bytes: it decodes, reads only what the contract can produce at its types, emits only its actions, uses no `RiskyFeature` the app hasn't allowed, and fits `maxBytes`. `check_draft` runs the gate without submitting. `templateSessionRoutes` adds the person's side: `POST templates` (contract, target, title, an optional starting document; `canCreate` decides who may) and `GET templates/draft` (the document to play, with its revision and sha256). Separate from `livery-sessions-server` so an app with only its own kinds needs no rc-players. *Planned:* a render of each draft with the contract's examples for `get_render` before the person's app has drawn one |
| `livery-sessions-client` | KMP, Ktor | The person's side: link, long-poll, comment, decide, share a render; resumes from stored capabilities |
| `livery-sessions-compose` | Compose Multiplatform, Material 3 | `LiveryAgentPanel` (presence line, the transcript naming the agent, composer with a pinned target), a fixed action row (**Use it**/**Create it**, **Adjust**, **Stop**, **Undo**, draft chips) that a long conversation can't scroll away, `SessionReview` with a preview slot the app fills, a session list, and "Connect an agent". **Mentions:** `@` in the composer offers the app's `MentionSource`: things on the preview (picking one pins the message to it, as its Comment button does) and the app's objects (inserted with their id, so the agent gets the one picked). **Turns** draw through a `TurnRenderer` slot: the default is the module's own rows (steps folded under "N steps" once the reply comes), on every target |
| `livery-sessions-agent-render` | Android, JVM, optional | A `TurnRenderer` on [agent-render](https://github.com/0xZhangKe/compose-agent-render) (Markdown replies, expandable tool calls). Separate because agent-render has no Wasm or iOS build and is young; the core module takes no third-party UI dependency. The `@` trigger detection is the module's own (a few lines), for the same reason |
| `livery-agent` | KMP | A built-in agent: a tool-calling loop over the same tools, on any OpenAI-compatible chat-completions endpoint. Keys stay in the caller's memory, and the turn and step limits are bounded |
| `livery-uibuilder` (extended) | KMP + JVM | Reach or run UI Builder (config, process, tokens), bounded app-to-browser handoffs (single use, 10 minutes), the `edits` channel publisher, and `livery ui-builder import` plus a reusable workflow that opens a pull request with the edited designs |

## UI Builder saves: the `edits` channel

A save in UI Builder should reach a designer's device within a minute, but it must never reach
everyone unreviewed.

- `main` is signed only by CI's key, so only merged pull requests reach it.
- A save is captured and published to an **`edits` channel**. That channel is signed by a server key
  limited to the catalog's contract prefix (PROTOCOL.md §3.2).
- Only devices that opt in read `edits`, layered above `main`. Layering needs several catalogs in
  one host, which homeassistant-remotecompose's plan lists as its L1.
- The import workflow opens the pull request. Once it merges, the edit is on `main` and its
  `edits` entry is pruned.
- A leaked server key reaches only opted-in devices, and only one contract prefix.

## Open questions

- Should a session be able to span kinds, for example a card design and the screen it sits on?
  Today an app starts them separately.
- How should a cloud draft be announced while the person's app is closed? The session server has
  no way to reach a phone. An app with a push channel can relay it.
- Should `livery-agent` stream its turns into the conversation, or only post the final reply?
