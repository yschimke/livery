# Public component integration evidence

Captured on 2026-11-09 in Chromium against a normally launched Compose Preview server,
using this branch's freshly built standalone viewer and real captured Remote Compose bytes.
These are local integration results, not deployed `preview.coo.ee` pages.

- [Full page](page.png) and [mobile width](mobile.png): signed `signed-1` revision, with
  only the publisher's public key in host configuration. A real canvas tap changed local
  quantity to 2; the published example remained 1. Sharing retains the canonical revision URL.
- [Dokka](dokka.png): actual generated Kotlin API documentation with two `fixture-1` embeds.
  The local development host explicitly admitted this unsigned fixture. Actions/reset were
  checked independently in each embed; ordinary KDoc links and sample source remain visible.

Reproduce with `web/core/test/host.mjs` and `samples/dokka-preview/test-browser.mjs`, following
their documented installation/configuration steps. The browser checks exercise runtime
behavior; the images record layout only. Drawn RC controls do not yet have proven screen-reader
semantics. No OS share sheet, authenticated Bluesky post or native Android render was captured.
