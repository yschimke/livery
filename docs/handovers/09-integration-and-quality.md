# 09 — Cross-host integration and quality evidence

Read [coordination](README.md). **Goal:** prove one publication works across hosts and expose
portability failures early. Own harness/fixtures consumption and evidence, not everyone else's
runtime implementation.

## Start now

Agree fixture version with 00 and artifact/reference renders with 01. Create a test matrix and
local environment recipe before hosts are finished. Record source commits, RC/catalog hashes,
player versions, fonts, device/browser versions and whether each dependency is mocked.
Use the quantity stepper only as a diagnostic; the headline evidence must use Confetti.

## Scenarios

1. Same pinned component in Confetti Android, standalone browser, Dokka HTML and Heron.
   Compare data/action semantics; document legitimate host/theme differences rather than
   claiming pixel identity. Test capability rejection and static fallback.
2. Theme/width/example changes, action log and reset; two previews maintain independent state.
   Data changes avoid document reload where supported. Font/image updates follow real cache rules.
3. UI Builder selected revision -> publication ready -> share handoff -> editable composer.
   Cover ordinary Bluesky web, Android share targets, Heron cold/warm/login, pasted link,
   cancellation, missing link card and failed thumbnail scrape. No false “posted” result.
4. Ordinary unsupported clients retain a readable link/card. Test public GET/HEAD, no-JS
   metadata, offline/withdrawn publication, bad signature, expiry and unavailable assets.
5. Heron sensitive content, nested click routing, rapid scrolling, list reuse, background,
   recreation and repeated cards. Measure frame time, memory and hidden network/player work.
6. Keyboard, screen reader, text scaling, focus and hit targets in web and native hosts.
   Detect accessibility limitations and report ownership; native rendering alone proves none.
7. Later: real session updates while disconnected/reconnected, public identity preserved,
   personal bookmark state excluded and real actions routed honestly.

## Deliver and limits

Return a reproducible local test recipe, automated checks where they test real contracts,
screenshots/short recordings and a pass/fail/blocked matrix. Agree practical budgets with host
owners using measured baselines; do not invent performance wins or use only screenshots as
proof of interaction. A critical blocker is an actionable issue with reproduction and owner.

Use fixture submission for posting tests until a real test account/post is explicitly authorised.
No production deploy or public post is implied by this handover. Keep evidence free of editor
tokens and account details. Feed generic player defects to `rc-players` and host issues to the
owning stream. Run required checks once per relevant change; avoid duplicative full-suite loops.
