# Parallel handovers: shareable RC components

Status: **Planned work; not started by writing these notes.**
Baseline plan commit: `23a0c09`. Read the [delivery plan](../RC_TILES_PLAN.md) first.
These notes are independently assignable briefs. Read the target repository's current
`AGENTS.md` before implementation and recheck all external branch heads.

## Product baseline

Confetti is the first real content/application; preview.coo.ee is the public viewer;
ui.coo.ee is the authoring/share entry point; Dokka is a documentation host; Heron is a
native receiving host. First share a component with example data and local interactions,
then share a real public conference session. AT standardisation follows working products.

A published design, a local preview session, public conference data and personal bookmarks
are separate. Never interpret a demo bookmark as an account write. No shared poll backend.

## Assignment map

| Note | Workstream | Can start now | Integration dependency |
| --- | --- | --- | --- |
| [00](00-contract-and-fixtures.md) | Descriptor, host semantics and fixtures | Yes; owns the shared interface | Content bundle from 01 for real fixtures |
| [01](01-confetti-content.md) | Confetti artifact and native reference | Yes; existing draft catalog | 00 for publication wrapper |
| [02](02-web-player.md) | Standalone web player and preview UI | Yes; local fixtures | 00/01; 03 serves its output |
| [03](03-publication-hosting.md) | Public publication and HTTP hosting | Yes; existing export/unfurl code | 00/01; 02 for interactive assets |
| [04](04-editor-sharing.md) | UI Builder Publish/share controls | Yes; mocked publication response | 03 before real end-to-end publication |
| [05](05-dokka-embedding.md) | KDoc/Dokka embed adapter | Yes; fixture URL/viewer | 02/03 for real preview |
| [06](06-heron-incoming-share.md) | Heron incoming share to composer | Yes; ordinary URL only | Independent of RC renderer |
| [07](07-heron-rendering.md) | Native RC external-link rendering | Yes; local artifact/provider | 00/01; 03 for live URL resolution |
| [08](08-confetti-public-sessions.md) | Real session identity, data and actions | Yes; read-only source/link discovery | 00/03/07 before integrated live sharing |
| [09](09-integration-and-quality.md) | Cross-host verification and evidence | Yes; matrix/harness | Consumes runnable slices incrementally |
| [10](10-at-interoperability-research.md) | Later AT mapping and interoperability research | Yes; research only | Working products before schema implementation |

All eleven can start discovery, fixtures or isolated work now. They are not eleven
independent end-to-end features. With fewer people, prioritise 00/01, 02, 03 and 07;
06 is a small independent app integration. Start 04/05 with mocks; 08/10 need not delay
the first component-preview demonstration.

## Shared interface checkpoint

00 owns descriptor/fixture semantics and records unresolved decisions centrally.
01 supplies real bytes, fonts, contracts and examples. At the first checkpoint, freeze
one revisioned fixture and the minimum resolution/publication interfaces. Each consumer
then implements against the same fixture version. Do not fork descriptors per host.

Contracts to agree: resolve(public URL) -> descriptor; publish(selected revision + public
example selection) -> public revision URL + descriptor location + fallback metadata;
load(document, values), update(values), dispatch(action), dispose().
These are conceptual boundaries, not approved method names or new public library APIs.
No descriptor-supplied arbitrary JavaScript or automatically authorised network actions.

## Ownership and merge order

- 00 owns shared descriptor docs/fixtures. It does not refactor the player or change the
  normative Livery protocol just to fit a demo.
- 02 owns Livery web extraction/player exports and preview UI assets; 03 owns hosting,
  publication persistence and HTTP policy. 03 integrates versioned assets from 02.
- 03 owns server API contracts; 04 owns editor UI and the browser share bridge. Agree DTOs
  through 00/03; avoid parallel edits to the same server route registry.
- 06 owns Heron's incoming intents and composer handoff; 07 owns repository/timeline RC
  presentation. A designated integration owner resolves shared navigation/DI registrations.
- 01 owns Confetti sample capture/catalog work; 08 owns resource resolution and session
  sharing. Changes to shared Confetti contracts are reviewed by both.
- 05 is an optional build/docs adapter. 09 owns integration harness/evidence and sends
  runtime defects to the relevant code owner. 10 makes no runtime changes.

Use separate branches/worktrees for parallel implementations, not concurrent Gradle or
generated-file writes in one checkout. Agree shared dependency versions before merging
cross-repository changes; test the published artifacts, not only composite builds.

Suggested merge order: shared fixture + content -> web and native fixture rendering ->
public hosting -> editor/docs consumers -> end-to-end shares -> real session adapter.
Incoming-share support can merge independently.

## Completion report required from every workstream

Return the branch/commit or reviewable patch, exact runtime/artifact versions, commands
and results, reproducible demo steps, remaining gaps and changes requested from other
owners. Distinguish implemented behaviour from mocked behaviour and inspected upstream
drafts. Include screenshots/recordings when they show the actual interaction.

Use the target repository's required checks. Shared Livery selection/binding/resource/trust
changes require normative docs and cross-port conformance updates under its AGENTS.md.
Do not claim a production rollout or a successful public post from a local demo.
