title: Protocol
order: 30

# Protocol

The normative specification is [`docs/PROTOCOL.md`](https://github.com/yschimke/livery/blob/main/docs/PROTOCOL.md):
files and addressing, the manifest, signing and trust, contracts and binding, selection, resources,
actions, decisions, feeds and events. The design rationale is in
[`docs/DESIGN.md`](https://github.com/yschimke/livery/blob/main/docs/DESIGN.md).

## At a glance

| Section | |
| --- | --- |
| §1 Files | `manifest.json`, `manifest.sig.json`, content-addressed blobs, assets, previews |
| §2 Manifest | contracts, designs, templates, targets, protocol levels (2 adds resources) |
| §3 Signing | Ed25519 over the exact manifest bytes; scoped keys; serial; expiry |
| §4 Contracts | typed inputs, lists, actions; binding refuses rather than drops |
| §5 Selection | eligibility, ranking; exact references play exactly or not at all |
| §6 Resources | image references, the image proxy, redirects, fonts |
| §7 Actions | named host actions with JSON arguments; numeric ones on launchers |
| §8 Decisions | local rules, rollouts, feeds, events |
