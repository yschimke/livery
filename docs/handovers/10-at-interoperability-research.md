# 10 — AT interoperability research, after a concrete product

Read [coordination](README.md) and the plan's eventual AT scope.
**Goal:** record constraints and a minimal future mapping while product streams proceed.
Research can start now; a new public Lexicon is not a dependency for any first-demo stream.

## Questions to answer with evidence

- Which part needs an AT record: publication metadata, public session identity, publisher
  discovery, or a combination? An ordinary Bluesky external link already distributes the demo.
- What existing community conventions can be reused? Compare actual Web Tiles mechanisms,
  not just names, and separate RC renderer requirements from portable metadata.
- How does an AT DID bind to the publisher, catalog signing key, content and permitted action
  origins? AT record provenance and Livery manifest/blob integrity are separate checks.
- How should AT CIDs refer to content-addressed RC/catalog assets? What remains externally
  hosted, how are redirects treated, and what are migration/deletion/availability semantics?
- Which actions are local preview operations, public navigation or authenticated app actions?
  Bluesky credentials do not authorise Confetti bookmarks. Avoid an all-purpose remote RPC schema.
- What is pinned versus following: source publication, RC design, public session data? Keep
  high-frequency updates outside repository writes unless the resource itself requires AT.
- Which parts are genuinely interoperable across independently implemented hosts/publishers?
  Identify fields that are merely internal implementation details.

## Deliver

A short source-linked decision memo, candidate mapping examples clearly marked experimental,
an alternatives table (HTTPS only / optional AT metadata / richer AT-native resource) and
questions for implementers. Read the current
[Lexicon guidance](https://atproto.com/guides/publishing-lexicons) and underlying specifications.

Describe a possible owned experimental namespace without registering/publishing it. No direct
outreach is authorised by this writing task; prepare questions and candidate collaborators
for later discussion. No standards claim, guaranteed client adoption or assumed core protocol
approval. Do not modify normative Livery semantics merely to fit a speculative Lexicon.

## Gate for implementation

Wait for measured Confetti/web/Heron examples and then an independent integration to determine
the smallest portable surface. Feed concrete constraints to 00, but do not block it on consensus.
A future proposal needs versioning, fixtures, fallback, identity/action policy and multiple
implementations. Personal bookmarks, editor credentials and private viewer state stay out of
public repository records.
