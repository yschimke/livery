title: Livery
order: 0
layout: home

<section class="hero">
  <div class="hero-text">
    <p class="eyebrow">Remote Compose for Android, Wear OS, iOS and desktop</p>
    <h1>Change what's on screen <span class="hl">without shipping an app.</span></h1>
    <p class="sub">Livery publishes <a href="https://developer.android.com/jetpack/androidx/releases/compose-remote">Remote Compose</a>
      templates as a signed catalog on any static host. Apps, launcher widgets, watches and
      notifications fetch, verify and cache them, fill them with typed data and play them, without
      ever waiting on the network.</p>
    <div class="ctas">
      <a class="button" href="#get-started">Get started</a>
      <a class="button ghost" href="concepts.html">Read the concepts</a>
      <a class="button ghost" href="https://github.com/yschimke/livery">GitHub</a>
    </div>
  </div>
  <div class="stage" aria-label="Illustration: one catalog played in an app, a launcher widget and a watch">
    <figure class="phone">
      <div class="screen">
        <div class="statusbar"><span>9:41</span><span class="dots"><i></i><i></i><i></i></span></div>
        <div class="mock banner">
          <div class="art"></div>
          <strong>Try Premium free</strong>
          <span>Offline downloads and no ads for 30 days.</span>
          <em>Start trial</em>
        </div>
        <img class="real" src="assets/home/tile-on.png" alt="Rendered sample: the Kitchen status tile, On · 80%" width="200" height="58">
        <div class="mock article">
          <div class="thumb"></div>
          <div><strong>Remote Compose templates in production</strong><span>Engineering · 2h</span>
            <span class="chips"><i>android</i><i>sdui</i></span></div>
        </div>
      </div>
      <figcaption>In-app slots</figcaption>
    </figure>
    <figure class="widget">
      <div class="mock launcher"><span class="bulb"></span><div><strong>Kitchen</strong><span>On · 80%</span></div></div>
      <figcaption>Launcher widget</figcaption>
    </figure>
    <figure class="watch">
      <div class="bezel"><div class="mock wear"><span class="ring"></span><strong>Kitchen</strong><span>On · 80%</span></div></div>
      <figcaption>Wear widget</figcaption>
    </figure>
  </div>
</section>

<section id="how" class="band">
  <h2>Design once. Publish anywhere. Play everywhere.</h2>
  <p class="section-sub">Three steps, and the app never needs a release to show a new look.</p>
  <ol class="steps">
    <li>
      <span class="num">1</span>
      <h3>Agree a contract</h3>
      <p>The contract is the only thing the app and the designer share: what a template may read and
        which actions it may emit. Author the <code>.rc</code> in any tool.</p>
<pre><code>"id": "promo/banner", "major": 1,
"inputs": {
  "headline": { "type": "string",
                "maxLength": 40 },
  "accent":   { "type": "color" } },
"actions": {
  "cta": { "kind": "navigate" } }</code></pre>
    </li>
    <li>
      <span class="num">2</span>
      <h3>Build, sign, publish</h3>
      <p>The <code>livery</code> CLI refuses a broken catalog, signs the manifest and writes
        content-addressed files for a GitHub release, a CDN or your own server.</p>
<pre><code>livery keygen acme
livery build catalog build/release \
  --github acme/app-templates \
  --tag catalog-1 \
  --key acme.key --key-id acme-1</code></pre>
    </li>
    <li>
      <span class="num">3</span>
      <h3>Play it in a slot</h3>
      <p>The client picks the right template for each player and size, binds data through the
        contract and draws it, from disk first, with your native fallback underneath.</p>
<pre><code>LiverySlot(
  key = tile,
  host = host,
  fallback = { NativeTile(tile) },
) {
  planner.forAppData(query, data)
}</code></pre>
    </li>
  </ol>
</section>

<section class="flow">
  <h2>How it fits together</h2>
  <p class="section-sub">Livery sits between "there is a <code>.rc</code> document" and "it is on
    screen with the right data", and reports what was shown and tapped.</p>
  <ol class="pipeline">
    <li><strong>Author</strong><span>any tool</span><small><code>.rc</code> + sidecar JSON</small></li>
    <li><strong>livery CLI</strong><span>validate, sign</span><small>manifest + blobs</small></li>
    <li><strong>Any host</strong><span>GitHub release, CDN</span><small>design server, assets</small></li>
    <li><strong>Your app</strong><span>fetch, verify, cache</span><small>select, bind, play</small></li>
  </ol>
  <dl class="glossary">
    <div><dt>Contract</dt><dd>What a template reads and may emit (<code>promo/banner@1</code>).</dd></div>
    <div><dt>Design</dt><dd>One look for a contract, as a picker shows it (<code>banner-classic</code>).</dd></div>
    <div><dt>Template</dt><dd>One values-free <code>.rc</code> per design and target.</dd></div>
    <div><dt>Catalog</dt><dd>A signed <code>manifest.json</code> plus content-addressed files.</dd></div>
  </dl>
</section>

<section class="looks band">
  <div class="looks-text">
    <h2>One template, any data, one gate</h2>
    <p>A template is values-free. The app, a decision server or a feed supplies the data, and
      <code>ContractBinder</code> checks it against the contract on the way in: the same check in the
      app, the publisher and agent tools. Data that doesn't fit is <strong>refused, not dropped</strong>,
      and the slot shows your native fallback instead.</p>
    <p>Designs are swappable too: ship a bolder banner to a rollout cohort or let people install a
      skin, and a design that can't play falls back to the automatic one, never to nothing.</p>
    <p><a href="concepts.html">Contracts, designs and templates →</a></p>
  </div>
  <div class="bindings">
    <figure><pre><code>{ "name": "Kitchen", "state": "On · 80%",
  "is_on": true, "level": 0.8 }</code></pre>
      <img src="assets/home/tile-on.png" alt="Rendered: Kitchen, On · 80%, bulb lit" width="200" height="58"></figure>
    <figure><pre><code>{ "name": "Kitchen", "state": "Off",
  "is_on": false, "level": 0 }</code></pre>
      <img src="assets/home/tile-off.png" alt="Rendered: Kitchen, Off, bulb grey" width="200" height="58"></figure>
    <figure><pre><code>{ "name": "Kitchen", "state": "Unavailable",
  "available": false }</code></pre>
      <img src="assets/home/tile-unavailable.png" alt="Rendered: Kitchen, Unavailable" width="200" height="58"></figure>
    <figure class="refused"><pre><code>{ "name": "Kitchen", "state": 80,
  "colour": "red" }</code></pre>
      <div class="verdict"><strong>Refused</strong><span><code>state</code>: expected string ·
        <code>colour</code>: not in <code>live/status-tile@1</code></span></div></figure>
  </div>
</section>

<section id="get-started" class="start">
  <h2>Get started</h2>
  <p class="section-sub">Libraries on Maven Central as <code>ee.schimke.composeai:&lt;module&gt;</code>;
    the <code>livery</code> and <code>livery-mcp</code> CLIs attached to each GitHub release.</p>
  <div class="tabs" data-tabs>
    <div role="tablist" aria-label="Install">
      <button role="tab" data-panel="tab-kotlin">Kotlin &amp; Compose</button>
      <button role="tab" data-panel="tab-swift">Swift</button>
      <button role="tab" data-panel="tab-cli">CLI</button>
      <button role="tab" data-panel="tab-agent">Agents</button>
    </div>
    <div role="tabpanel" id="tab-kotlin">
<pre><code>dependencies {
  implementation("ee.schimke.composeai:livery-client:&lt;version&gt;")   // fetch, verify, cache, select, bind
  implementation("ee.schimke.composeai:livery-compose:&lt;version&gt;")  // LiveryTemplate, LiverySlot
  // livery-launcher-widgets, livery-wear-widgets, livery-notifications
}</code></pre>
      <p>Android, JVM, iOS and Wasm from one Kotlin Multiplatform client. <a href="getting-started.html#5-read-it-in-the-app">Set up the repository →</a></p>
    </div>
    <div role="tabpanel" id="tab-swift">
<pre><code>// Package.swift
.package(url: "https://github.com/yschimke/livery", branch: "main")</code></pre>
      <p><code>Livery</code> and <code>LiveryUI</code> for iOS and macOS, held to the Kotlin client by shared conformance fixtures. <a href="platforms.html#swift">Swift details →</a></p>
    </div>
    <div role="tabpanel" id="tab-cli">
<pre><code>git clone https://github.com/yschimke/livery &amp;&amp; cd livery
./gradlew :livery-publish:installDist
export PATH="$PWD/livery-publish/build/install/livery/bin:$PATH"
livery build samples/catalog build/sample-catalog</code></pre>
      <p>Validate, sign and lay out a catalog for any host. <a href="hosting.html">Hosting →</a></p>
    </div>
    <div role="tabpanel" id="tab-agent">
<pre><code>./gradlew :livery-mcp:installDist
claude mcp add livery -- /abs/path/livery-mcp path/to/catalog \
  --write --preview-out build/previews</code></pre>
      <p>Contracts, the data gate and renders for any MCP agent, but never the signing key. <a href="ai-workflows.html">AI workflows →</a></p>
    </div>
  </div>
  <ol class="next">
    <li><a href="getting-started.html#2-make-a-signing-key"><strong>Make a signing key</strong><span>Your apps embed the public half.</span></a></li>
    <li><a href="getting-started.html#3-write-a-catalog"><strong>Write a catalog</strong><span>A directory of contracts and designs.</span></a></li>
    <li><a href="getting-started.html#4-publish-it-as-a-github-release"><strong>Publish a release</strong><span>Any static host works.</span></a></li>
  </ol>
</section>

<section id="use-cases" class="band">
  <h2>What you can build</h2>
  <p class="section-sub">Each page goes from the contract to a deployment checklist.</p>
  <div class="cards">
    <a class="card" href="use-cases/ads.html"><span class="tag">in-app</span><h3>Interactive ads</h3><p>Your ad server answers a placement with a template reference and its data; Livery draws exactly that creative, or nothing.</p></a>
    <a class="card" href="use-cases/promotions.html"><span class="tag">in-app</span><h3>Promotions and growth</h3><p>Ship rules in the catalog (window, audience, rollout, frequency cap); the device picks the promotion, offline.</p></a>
    <a class="card" href="use-cases/feeds.html"><span class="tag">in-app</span><h3>Feeds</h3><p>A feed page is a list of template references plus data; a handful of templates draw thousands of items.</p></a>
    <a class="card" href="use-cases/live-content.html"><span class="tag">in-app</span><h3>Live content</h3><p>The app pushes its own live values into a template, and people install skins for it.</p></a>
    <a class="card" href="use-cases/launcher-widgets.html"><span class="tag">launcher</span><h3>Launcher widgets</h3><p>Subclass one provider; Livery instantiates the template for each widget's size and routes taps back.</p></a>
    <a class="card" href="use-cases/wear-widgets.html"><span class="tag">wear</span><h3>Wear widgets</h3><p>Subclass one Glance Wear widget; Livery frames the template in each watch's container.</p></a>
    <a class="card" href="use-cases/notifications.html"><span class="tag">notifications</span><h3>Notifications</h3><p>Render a template for a notification's expanded body; taps come back to your receiver.</p></a>
    <a class="card planned" href="use-cases/web.html"><span class="tag">web · planned</span><h3>The web</h3><p>A <code>&lt;livery-template&gt;</code> element over the TypeScript player.</p></a>
  </div>
</section>

<section id="features">
  <h2>Built for templates in production</h2>
  <div class="features">
    <div><h3>Never waits on the network</h3><p>Slots draw from disk, then the bundled catalog, then your native fallback. <code>refresh()</code> runs in the background.</p></div>
    <div><h3>Refused, not dropped</h3><p>One gate, <code>ContractBinder</code>, checks data in the app, the publisher, decision servers and agent tools alike.</p></div>
    <div><h3>Signed catalogs</h3><p>Ed25519 manifests, partner keys scoped to a contract prefix, rotation, freshness and a kill switch. <a href="signing.html">Signing →</a></p></div>
    <div><h3>Any static host</h3><p>GitHub releases, a CDN or a design server. Content-addressed files cache forever. <a href="hosting.html">Hosting →</a></p></div>
    <div><h3>The right template per player</h3><p><code>TemplateSelector</code> matches surface, Remote Compose level, profiles and size.</p></div>
    <div><h3>Agents included</h3><p><code>livery-mcp</code> gives agents contracts, validation and PNG previews; drafts stay unsigned. <a href="ai-workflows.html">AI workflows →</a></p></div>
    <div><h3>Kotlin and Swift agree</h3><p>Both ports run the same <code>conformance/</code> fixtures for selection, binding, resources and trust.</p></div>
    <div><h3>An open protocol</h3><p>A normative, versioned manifest: new fields have defaults, old clients ignore what they don't know. <a href="protocol.html">Protocol →</a></p></div>
  </div>
</section>

<section class="platforms band">
  <h2>Platforms</h2>
  <ul class="status">
    <li><span class="dot ok"></span>Kotlin Multiplatform client (Android, JVM, iOS, Wasm)</li>
    <li><span class="dot ok"></span>Compose Multiplatform (<code>LiveryTemplate</code>, <code>LiverySlot</code>)</li>
    <li><span class="dot ok"></span>Swift package (<code>Livery</code>, <code>LiveryUI</code>)</li>
    <li><span class="dot ok"></span>Launcher widgets (Android 16+)</li>
    <li><span class="dot ok"></span>Wear widgets (Glance Wear)</li>
    <li><span class="dot ok"></span>Agent tools (<code>livery-mcp</code>) and headless previews</li>
    <li><span class="dot planned"></span>Phone-to-watch sync, web element, reference server <em>Planned</em></li>
  </ul>
  <p><a href="platforms.html">Platform details →</a></p>
</section>

<section class="closing">
  <h2>Your next screen change doesn't need a release.</h2>
  <div class="ctas center">
    <a class="button" href="getting-started.html">Get started</a>
    <a class="button ghost" href="concepts.html">Concepts</a>
  </div>
</section>
