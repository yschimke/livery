// End-to-end in headless Chromium: build the page, serve it with a sample catalog, and check that
// Livery components draw in the page (no iframe), that tapping a template sends the agent its
// event, that a `set` writes the data model back, and that data changes set values on the playing
// document instead of reloading it.
//
//   ./gradlew :livery-web:webDist :livery-publish:installDist && npm run build && npm test
import { build } from 'esbuild';
import { cpSync, existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { dirname, extname, join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { readFile } from 'node:fs/promises';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const repo = join(root, '../..');
const site = join(here, 'site');
const out = process.env.LIVERY_RENDER_OUT;
const players = process.env.LIVERY_TEST_PLAYER ? [process.env.LIVERY_TEST_PLAYER] : ['ts', 'cmp'];
rmSync(site, { recursive: true, force: true });
mkdirSync(site, { recursive: true });

// The sample catalog plus the two-way one, built by the livery CLI from the trees
// `./gradlew :livery-capture:sampleCatalogs` lays out (their captured designs are build output).
const src = join(site, 'catalog-src');
if (!existsSync(join(repo, 'livery-capture/build/samples/catalog'))) throw new Error('run ./gradlew :livery-capture:sampleCatalogs first');
cpSync(join(repo, 'livery-capture/build/samples/catalog'), src, { recursive: true });
for (const d of ['contracts', 'designs']) cpSync(join(repo, 'livery-capture/build/samples/two-way', d), join(src, d), { recursive: true });
const cli = join(repo, 'livery-publish/build/install/livery/bin/livery');
if (!existsSync(cli)) throw new Error('run ./gradlew :livery-publish:installDist first');
execFileSync(cli, ['build', src, join(site, 'catalog')], { stdio: 'inherit' });

// This package's Wasm module beside the page.
const require = createRequire(import.meta.url);
cpSync(join(root, 'dist/livery-web'), join(site, 'livery-web'), { recursive: true });

if (players.includes('cmp')) cpSync(join(root, 'dist/livery-web-compose'), join(site, 'livery-web-compose'), { recursive: true });

// Allow the same browser suite to exercise each supported React major without changing the lockfile.
const reactRoot = process.env.LIVERY_REACT_ROOT;
const reactAliases = reactRoot ? { react: join(reactRoot, 'react'), 'react-dom': join(reactRoot, 'react-dom') } : undefined;

// The page, bundled; @js-joda/core (imported by the Wasm module's glue) through an import map.
await build({ entryPoints: [join(here, 'app.ts')], alias: reactAliases, bundle: true, format: 'esm', target: 'es2022', outfile: join(site, 'app.js'), external: ['./livery-web/*'], logLevel: 'warning' });
await build({ stdin: { contents: "export * from '@js-joda/core';", resolveDir: root }, bundle: true, format: 'esm', outfile: join(site, 'js-joda.mjs'), logLevel: 'warning' });
writeFileSync(join(site, 'index.html'), `<!doctype html><meta charset="utf-8">
<script type="importmap">{"imports": {"@js-joda/core": "./js-joda.mjs"}}</script>
<style>body { font-family: sans-serif; width: 420px; } [data-livery-component="ShopQuantity"] { --livery-a2ui-height: 120px; }</style>
<div id="app"></div><div id="second"></div><script type="module" src="./app.js"></script>`);

const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.wasm': 'application/wasm', '.json': 'application/json', '.rc': 'application/octet-stream', '.png': 'image/png', '.ttf': 'font/ttf' };
const server = createServer(async (req, res) => {
  const asked = join(site, decodeURIComponent(new URL(req.url, 'http://x').pathname));
  const path = asked.endsWith('/') ? join(asked, 'index.html') : asked;
  try {
    const body = await readFile(path);
    res.writeHead(200, { 'content-type': TYPES[extname(path)] ?? 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end(); }
}).listen(0, '127.0.0.1');
await new Promise((r) => server.once('listening', r));
const base = `http://127.0.0.1:${server.address().port}/`;

const { chromium } = require('playwright');
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_EXECUTABLE_PATH ?? process.env.CHROMIUM_PATH ?? process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
let failed = 0;
for (const player of players) {
console.log(`Player: ${player}`);
const context = await browser.newContext({ viewport: { width: 480, height: 700 }, deviceScaleFactor: Number(process.env.LIVERY_DENSITY ?? process.env.LIVERY_TEST_DPR ?? 1) });
const page = await context.newPage();
let releaseDecision;
const decisionGate = new Promise((resolve) => { releaseDecision = resolve; });
await page.route('https://decisions.example/v1/decisions/query', async (route) => {
  if (route.request().method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: { 'access-control-allow-origin': '*', 'access-control-allow-methods': 'POST', 'access-control-allow-headers': 'content-type' } });
    return;
  }
  await decisionGate;
  await route.fulfill({ contentType: 'application/json', headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify({
    placement: 'assistant-offer', decisionId: 'server:browser', template: { designId: 'banner-classic' },
    data: { headline: 'Server offer', cta: 'Go' }, ttlSeconds: 60,
    experiments: { 'offer-copy': 'treatment' },
  }) });
});
const logs = [];
const requests = [];
page.on('request', (r) => requests.push(r.url()));
if (player === 'cmp') await page.addInitScript(() => Object.defineProperty(navigator, 'languages', { configurable: true, value: ['en-US@posix', 'en_GB'] }));
page.on('console', (m) => logs.push(`${m.type()}: ${m.text()}`));
page.on('pageerror', (e) => logs.push(`pageerror: ${e.message}`));
page.on('response', (r) => { if (r.status() >= 400) logs.push(`${r.status()} ${r.url()}`); });
const check = (ok, what) => { console.log(`${ok ? 'ok' : 'FAIL'} - ${what}`); if (!ok) failed++; };
try {
  await page.goto(`${base}?player=${player}&renderer=${process.env.LIVERY_RENDERER ?? 'lit'}`);
  await page.waitForFunction(() => window.__livery, null, { timeout: 60_000 });
  const components = await page.evaluate(() => window.__livery.components);
  check(components.includes('PromoBanner') && components.includes('ShopQuantity'), `components advertised: ${components}`);

  // Each Livery component draws on a canvas in the page itself: no iframe.
  const statsOf = (selector) => page.evaluate((sel) => {
    const find = (root) => {
      const hit = root.querySelector(sel); if (hit) return hit;
      for (const el of root.querySelectorAll('*')) if (el.shadowRoot) { const f = find(el.shadowRoot); if (f) return f; }
      return null;
    };
    const count = (root, tag) => [...root.querySelectorAll('*')].reduce((n, e) => n + (e.matches(tag) ? 1 : 0) + (e.shadowRoot ? count(e.shadowRoot, tag) : 0), 0);
    const el = find(document);
    return el ? { ...el.liveryStats, iframes: count(el.shadowRoot, 'iframe'), canvases: count(el.shadowRoot, 'canvas') } : null;
  }, selector);
  const loaded = (selector) => page.waitForFunction((sel) => {
    const find = (root) => {
      const hit = root.querySelector(sel); if (hit) return hit;
      for (const el of root.querySelectorAll('*')) if (el.shadowRoot) { const f = find(el.shadowRoot); if (f) return f; }
      return null;
    };
    return (find(document)?.liveryStats?.loads ?? 0) > 0;
  }, selector, { timeout: 60_000 });
  await loaded('#app [data-livery-component="PromoBanner"]');
  await loaded('#app [data-livery-component="ShopQuantity"]');
  const promoStats = await statsOf('#app [data-livery-component="PromoBanner"]');
  check(promoStats.iframes === 0 && promoStats.canvases === 1, `drawn on a canvas in the page, no iframe: ${JSON.stringify(promoStats)}`);
  await page.waitForTimeout(300);
  const initialRequests = requests.length;
  const timing = await page.evaluate(() => ({ sampleReadyMs: performance.now(), heapBytes: performance.memory?.usedJSHeapSize, resources: performance.getEntriesByType('resource').filter((r) => r.name.includes('/livery-web')).map((r) => ({ name: r.name.split('/').pop(), bytes: r.decodedBodySize, ms: r.duration })) }));
  timing.accessibility = await page.locator('#app [data-livery-component="PromoBanner"]').ariaSnapshot();
  console.log(`metrics ${player}: ${JSON.stringify(timing)}`);
  if (player === 'cmp') check(await page.evaluate(() => performance.getEntriesByType('resource')
    .filter((r) => r.name.includes('/livery-web-compose/'))
    .every((r) => r.responseEnd <= window.__livery.catalogPreparedAt)), 'CMP, Skiko and fonts finish loading before catalog preparation returns');
  if (out) { mkdirSync(out, { recursive: true }); writeFileSync(join(out, `a2ui-web-${player}-metrics.json`), JSON.stringify(timing, null, 2) + '\n'); }
  check(player !== 'ts' || !requests.some((r) => r.includes('livery-web-compose') || r.includes('skiko')), 'the default player fetches no CMP runtime, Skiko or fonts');
  if (out) { mkdirSync(out, { recursive: true }); await page.screenshot({ path: join(out, `a2ui-web-${player}.png`) }); }

  // Drawn, not just loaded: the banner has its surface and its text.
  const colours = async (selector) => {
    const shot = await (await page.$(selector)).screenshot();
    return page.evaluate(async (b64) => {
      const img = new Image(); img.src = `data:image/png;base64,${b64}`; await img.decode();
      const c = document.createElement('canvas'); c.width = img.width; c.height = img.height;
      const g = c.getContext('2d'); g.drawImage(img, 0, 0);
      const d = g.getImageData(0, 0, c.width, c.height).data;
      let notWhite = 0; const seen = new Set();
      for (let i = 0; i < d.length; i += 4) { if (d[i] < 245 || d[i + 1] < 245 || d[i + 2] < 245) notWhite++; seen.add((d[i] >> 4) << 8 | (d[i + 1] >> 4) << 4 | (d[i + 2] >> 4)); }
      return { notWhite: notWhite / (d.length / 4), distinct: seen.size, hash: shot64(d) };
      function shot64(d) { let h = 0; for (let i = 0; i < d.length; i += 97) h = (h * 31 + d[i]) | 0; return h; }
    }, shot.toString('base64'));
  };
  // Drawn, not just loaded: the banner has its surface and its text.
  const banner = await colours('#app [data-livery-component="PromoBanner"]');
  check(banner.notWhite > 0.5 && banner.distinct > 8, `the banner draws its surface and text: ${JSON.stringify(banner)}`);

  // Where a template draws a button: the centre of its saturated pixels (the right-most button's, with `right`).
  const buttonIn = async (selector, which = 'any') => {
    const el = await page.$(selector);
    const box = await el.boundingBox();
    const shot = await el.screenshot();
    const at = await page.evaluate(async ({ b64, which }) => {
      const img = new Image(); img.src = `data:image/png;base64,${b64}`; await img.decode();
      const c = document.createElement('canvas'); c.width = img.width; c.height = img.height;
      const g = c.getContext('2d'); g.drawImage(img, 0, 0);
      const d = g.getImageData(0, 0, c.width, c.height).data;
      const hits = [];
      for (let y = 0; y < c.height; y++) for (let x = 0; x < c.width; x++) {
        const i = (y * c.width + x) * 4, mx = Math.max(d[i], d[i + 1], d[i + 2]), mn = Math.min(d[i], d[i + 1], d[i + 2]);
        if (mx - mn > 60) hits.push([x, y]);
      }
      if (!hits.length) return null;
      const right = Math.max(...hits.map((h) => h[0]));
      const mine = which === 'right' ? hits.filter((h) => h[0] > right - 36) : hits;
      return { x: mine.reduce((a, h) => a + h[0], 0) / mine.length, y: mine.reduce((a, h) => a + h[1], 0) / mine.length, w: img.width };
    }, { b64: shot.toString('base64'), which });
    if (!at) throw new Error(`no button drawn in ${selector}`);
    const scale = box.width / at.w;
    return { x: box.x + at.x * scale, y: box.y + at.y * scale };
  };
  const tap = async (selector, which) => { const p = await buttonIn(selector, which); await page.mouse.click(p.x, p.y); };

  // Delivers a host named action to a Livery element, as its player's callback would.
  const hostAction = (selector, a) => page.evaluate(({ sel, a }) => {
    const find = (root) => { const hit = root.querySelector(sel); if (hit) return hit; for (const el of root.querySelectorAll('*')) if (el.shadowRoot) { const f = find(el.shadowRoot); if (f) return f; } return null; };
    return find(document).liveryHostAction(a);
  }, { sel: selector, a });

  // A real tap on the drawn "Shop now": the template's host action, through the gate, to the agent.
  await tap('#app [data-livery-component="PromoBanner"]');
  await page.waitForFunction(() => window.__livery.actions.length > 0, null, { timeout: 10_000 }).catch(() => {});
  const action = await page.evaluate(() => window.__livery.actions[0]);
  check(action?.name === 'startTrial' && action?.sourceComponentId === 'promo' && action?.context?.from === 'chat' && typeof action?.context?.route === 'string',
    `tapping the CTA sends the agent its event, with the agent's context and the template's declared route: ${JSON.stringify(action)}`);
  // An argument the contract doesn't declare never reaches the agent (delivered as the player would).
  await hostAction('#app [data-livery-component="PromoBanner"]', { name: 'cta', valueType: 'string', value: '{"route":"/premium","extra":"x"}' });
  await page.waitForFunction(() => window.__livery.actions.length > 1, null, { timeout: 10_000 }).catch(() => {});
  const undeclared = await page.evaluate(() => window.__livery.actions[1]);
  check(undeclared?.context?.route === '/premium' && !('extra' in (undeclared?.context ?? {})), `nothing undeclared reaches the agent: ${JSON.stringify(undeclared)}`);
  // Livery's own events say where the agent placed the template: a2ui:<surfaceId>/<componentId>.
  const events = await page.evaluate(() => window.__livery.events());
  const promo = events.filter((e) => e.designId && e.decisionId === 'a2ui:chat-1/promo');
  check(promo.some((e) => e.type === 'Impression') && promo.some((e) => e.type === 'Action' && e.action === 'cta'),
    `events carry the A2UI decision id: ${JSON.stringify(events.map((e) => [e.type, e.decisionId, e.action]))}`);

  // A real tap on "+": the template's `set` writes the data model, and the same document takes the new value.
  const before = await statsOf('#app [data-livery-component="ShopQuantity"]');
  await tap('#app [data-livery-component="ShopQuantity"]', 'right');
  await page.waitForFunction(() => window.__livery.data('/cart/quantity') === 2, null, { timeout: 10_000 }).catch(() => {});
  check(await page.evaluate(() => window.__livery.data('/cart/quantity')) === 2, 'tapping + wrote /cart/quantity = 2');
  await page.waitForTimeout(300);
  const after = await statsOf('#app [data-livery-component="ShopQuantity"]');
  check(after.loads === before.loads && after.sets > before.sets, `and the document stayed, its value set: ${JSON.stringify({ before, after })}`);

  // The agent changes the data: the banner's headline moves, in the same document.
  const promoBefore = await statsOf('#app [data-livery-component="PromoBanner"]');
  await page.evaluate(() => window.__livery.send([{ version: 'v0.9', updateDataModel: { surfaceId: 'chat-1', path: '/offer/title', value: 'Summer sale' } }]));
  await page.waitForFunction((n) => {
    const find = (root) => { const hit = root.querySelector('#app [data-livery-component="PromoBanner"]'); if (hit) return hit; for (const el of root.querySelectorAll('*')) if (el.shadowRoot) { const f = find(el.shadowRoot); if (f) return f; } return null; };
    return (find(document)?.liveryStats?.sets ?? 0) > n;
  }, promoBefore.sets, { timeout: 10_000 }).catch(() => {});
  await page.waitForTimeout(300);
  const promoAfter = await statsOf('#app [data-livery-component="PromoBanner"]');
  const redrawn = await colours('#app [data-livery-component="PromoBanner"]');
  check(promoAfter.loads === promoBefore.loads && promoAfter.sets > promoBefore.sets && redrawn.hash !== banner.hash,
    `a data-model update sets the headline on the playing document, which redraws: ${JSON.stringify({ promoBefore, promoAfter })}`);
  if (out) await page.screenshot({ path: join(out, `a2ui-web-${player}-updated.png`) });

  // A second catalog with the same component names draws with its own session, and its actions reach
  // its own processor, not the first one's.
  await loaded('#second [data-livery-component="PromoBanner"]');
  const firstCount = await page.evaluate(() => window.__livery.actions.length);
  await tap('#second [data-livery-component="PromoBanner"]');
  await page.waitForFunction(() => window.__livery.actions2.length > 0, null, { timeout: 10_000 }).catch(() => {});
  const second = await page.evaluate(() => window.__livery.actions2[0]);
  check(second?.name === 'secondTrial' && await page.evaluate(() => window.__livery.actions.length) === firstCount, `a second catalog's component reports to its own processor: ${JSON.stringify(second)}`);

  // The agent's createSurface theme colours its templates over the page's CSS theme; a surface
  // without one takes the page's.
  const themes = await page.evaluate(() => ({
    agent: document.querySelector('#app [data-livery-component="ShopQuantity"]')?.liveryTheme?.primary,
    page: document.querySelector('#second [data-livery-component="PromoBanner"]')?.liveryTheme?.primary,
  }));
  check(themes.agent === '#FF00897B' && typeof themes.page === 'string' && themes.page !== themes.agent, `a surface's primaryColor is its templates' primary: ${JSON.stringify(themes)}`);
  const shot = await (await page.$('#app [data-livery-component="ShopQuantity"]')).screenshot();
  const teal = await page.evaluate(async (b64) => {
    const img = new Image(); img.src = `data:image/png;base64,${b64}`; await img.decode();
    const c = document.createElement('canvas'); c.width = img.width; c.height = img.height;
    const g = c.getContext('2d'); g.drawImage(img, 0, 0);
    const d = g.getImageData(0, 0, c.width, c.height).data;
    let n = 0;
    for (let i = 0; i < d.length; i += 4) if (Math.abs(d[i] - 0x00) < 24 && Math.abs(d[i + 1] - 0x89) < 24 && Math.abs(d[i + 2] - 0x7b) < 24) n++;
    return n;
  }, shot.toString('base64'));
  check(teal > 200, `and the stepper draws in it: ${teal} pixels of #00897B`);

  // The height rule: the template's own height when the page gives none, the page's when it does.
  const heights = await page.evaluate(() => ({
    banner: document.querySelector('#app [data-livery-component="PromoBanner"]').clientHeight,
    stepper: document.querySelector('#app [data-livery-component="ShopQuantity"]').clientHeight,
  }));
  check(heights.banner === (player === 'ts' ? 112 : 160) && heights.stepper === 120, `the banner takes its player's measured or authored height, the stepper the page's 120px: ${JSON.stringify(heights)}`);

  await page.evaluate(() => window.__livery.send([{ version: 'v0.9', updateDataModel: { surfaceId: 'chat-1', path: '/offer/title', value: 'Unavailable glyph: 𐀀' } }]));
  await page.waitForTimeout(800);
  check(requests.length === initialRequests, 'actions, live updates and missing glyphs make no network requests');

  // A container-only resize must resize the viewport without a browser window resize event.
  await page.evaluate(() => { document.body.style.width = '380px'; });
  await page.waitForTimeout(400);
  const resizedCount = await page.evaluate(() => window.__livery.actions.length);
  await tap('#app [data-livery-component="PromoBanner"]');
  await page.waitForFunction((n) => window.__livery.actions.length > n, resizedCount);
  check((await statsOf('#app [data-livery-component="PromoBanner"]')).loads === 1, 'resizing preserves the document and real taps still work');

  // Detach and reconnect the same surface: release its viewport and create exactly one new one.
  await page.evaluate(() => {
    window.__livery.detachSecond();
  });
  await page.waitForTimeout(100);
  check(await page.evaluate(() => {
    const count = (r) => [...r.querySelectorAll('*')].reduce((n, e) => n + (e.tagName === 'CANVAS' ? 1 : 0) + (e.shadowRoot ? count(e.shadowRoot) : 0), 0);
    return count(document.querySelector('#second')) === 0;
  }), 'disconnect releases every canvas');
  await page.evaluate(() => window.__livery.reconnectSecond());
  await loaded('#second [data-livery-component="PromoBanner"]');
  check((await statsOf('#second [data-livery-component="PromoBanner"]')).canvases === 1, 'reconnect creates exactly one fresh canvas');
  const reconnectCount = await page.evaluate(() => window.__livery.actions2.length);
  await tap('#second [data-livery-component="PromoBanner"]');
  await page.waitForFunction((n) => window.__livery.actions2.length > n, reconnectCount);
  check(true, 'the reconnected component still routes real taps');
  const lifecycle = await page.evaluate(() => window.__livery.exercisePlayers());
  check(lifecycle.drawn && (player === 'ts' || lifecycle.cmpCount === 5), `twelve more components draw, with at most eight CMP viewports across the page: ${JSON.stringify(lifecycle)}`);
  check(lifecycle.queued[0] === false && lifecycle.queued[1] === true && lifecycle.reloaded && lifecycle.forgotten, 'queued shows supersede, changed keys reload, forget forces a reload');
  check(lifecycle.cancelled === false && lifecycle.remaining === 0, 'dispose cancels an in-flight show and leaves no canvas');
  check(!logs.some((l) => l.startsWith('pageerror:')), `no uncaught browser errors: ${logs.filter((l) => l.startsWith('pageerror:'))}`);

  // The automatic banner remains visible during a delayed contract decision, then gets server data.
  const queryBefore = await statsOf('#app [data-livery-component="PromoBanner"]');
  const querySent = page.waitForRequest((request) => request.url() === 'https://decisions.example/v1/decisions/query' && request.method() === 'POST');
  await page.evaluate(() => window.__livery.send([{ version: 'v0.9', updateComponents: { surfaceId: 'chat-1', components: [
    { id: 'promo', component: 'PromoBanner', headline: 'Waiting offer', cta: 'Wait', template: { query: { offer: 'summer' } } },
  ] } }]));
  await page.waitForFunction((n) => document.querySelector('#app [data-livery-component="PromoBanner"]')?.liveryStats?.sets > n, queryBefore.sets);
  const decisionRequest = (await querySent).postDataJSON();
  check(decisionRequest?.query?.contract === 'promo/banner@1' && decisionRequest?.params?.offer === 'summer' && decisionRequest?.context?.locale === 'en-GB',
    `query sends device context and agent params: ${JSON.stringify(decisionRequest)}`);
  const waiting = await statsOf('#app [data-livery-component="PromoBanner"]');
  check((await page.locator('#app [data-livery-component="PromoBanner"] canvas').count()) > 0, 'automatic canvas draws while the server waits');
  await page.evaluate(() => { window.__livery.events(); window.__queryEvents = []; });
  releaseDecision();
  await page.waitForFunction((n) => document.querySelector('#app [data-livery-component="PromoBanner"]')?.liveryStats?.sets > n, waiting.sets);
  await page.waitForFunction(() => {
    window.__queryEvents.push(...window.__livery.events());
    return window.__queryEvents.some((e) => e.type === 'Impression' && e.placement === 'assistant-offer');
  }, null, { timeout: 10_000 });
  const decided = await statsOf('#app [data-livery-component="PromoBanner"]');
  check(decided.loads === waiting.loads, 'the decided presentation reuses the automatic document without reloading');
  await hostAction('#app [data-livery-component="PromoBanner"]', { name: 'cta', valueType: 'string', value: '{"route":"/sale"}' });
  const queryEvents = await page.evaluate(() => [...window.__queryEvents, ...window.__livery.events()]);
  check(queryEvents.some((e) => e.type === 'Action' && e.placement === 'assistant-offer' && e.decisionId === 'a2ui:chat-1/promo'), 'decided actions retain the A2UI event id and server placement');
  const decidedImpressions = queryEvents.filter((e) => e.type === 'Impression' && e.placement === 'assistant-offer');
  check(decidedImpressions.length === 1 && decidedImpressions[0].decisionId === 'a2ui:chat-1/promo' && decidedImpressions[0].experiments?.['offer-copy'] === 'treatment',
    'the reused document reports one decision impression with its placement and experiment attribution');

  // Changing fallback props re-renders the cached decision; it is still the same presentation.
  await page.evaluate(() => window.__livery.send([{ version: 'v0.9', updateComponents: { surfaceId: 'chat-1', components: [
    { id: 'promo', component: 'PromoBanner', headline: 'Updated fallback', cta: 'Wait', template: { query: { offer: 'summer' } } },
  ] } }]));
  await page.waitForTimeout(300);
  const repeatedEvents = await page.evaluate(() => window.__livery.events());
  check(!repeatedEvents.some((e) => e.type === 'Impression' && e.placement === 'assistant-offer') && (await statsOf('#app [data-livery-component="PromoBanner"]')).loads === decided.loads,
    'updating the cached decision neither repeats its impression nor reloads its document');

  const problems = await page.evaluate(() => window.__livery.problems);
  check(problems.length === 0, `no problems reported: ${JSON.stringify(problems)}`);
  const memoryOnline = await page.evaluate(() => window.__livery.cachedComponents({ persistentCache: false }));
  check(memoryOnline?.includes('PromoBanner'), 'memory-only sessions work while online');
  const withoutLocks = await page.evaluate(async () => {
    Object.defineProperty(navigator, 'locks', { configurable: true, value: undefined });
    try { return { persistent: await window.__livery.cachedComponents({}), memory: await window.__livery.cachedComponents({ persistentCache: false }) }; }
    finally { delete navigator.locks; }
  });
  check(withoutLocks.persistent === null && withoutLocks.memory?.includes('PromoBanner'), 'without Web Locks persistent sessions fail closed and memory-only sessions work');


  // Simulate an interrupted prefetch: keep the manifest and metadata, remove all template blobs.
  const removedBlobs = await page.evaluate(() => new Promise((resolve, reject) => {
    const open = indexedDB.open('livery-v1', 1);
    open.onerror = () => reject(open.error);
    open.onsuccess = () => {
      const db = open.result;
      const tx = db.transaction('entries', 'readwrite');
      const store = tx.objectStore('entries');
      const request = store.getAllKeys();
      let removed = 0;
      request.onsuccess = () => {
        for (const key of request.result) if (String(key).includes('/blob/')) { store.delete(key); removed++; }
      };
      tx.oncomplete = () => { db.close(); resolve(removed); };
      tx.onabort = () => { db.close(); reject(tx.error); };
    };
  }));
  check(removedBlobs > 0, 'the partial-cache regression retains metadata but removes downloaded blobs');
  let resumeBlobs;
  const blobsBlocked = new Promise((resolve) => { resumeBlobs = resolve; });
  const delayedBlobs = async (route) => { await blobsBlocked; await route.continue(); };
  await page.route('**/catalog/**/*.rc', delayedBlobs);
  const repairing = page.waitForRequest((request) => request.url().includes('/catalog/') && request.url().endsWith('.rc'));
  await page.reload({ waitUntil: 'commit' });
  await repairing;
  await page.waitForTimeout(100);
  check(await page.evaluate(() => !window.__livery), 'opening waits for repairs instead of registering an empty catalog');
  resumeBlobs();
  await page.waitForFunction(() => window.__livery, null, { timeout: 60_000 });
  await loaded('#app [data-livery-component="PromoBanner"]');
  await loaded('#app [data-livery-component="ShopQuantity"]');
  check(await page.evaluate(() => window.__livery.components.includes('PromoBanner')), 'opening a partial cache repairs blobs before advertising components');
  await page.unrouteAll({ behavior: 'wait' });

  // Keep the app shell available, but make every catalog/blob/image request fail as if offline.
  await page.route('**/catalog/**', (route) => route.abort('internetdisconnected'));
  await page.reload();
  await page.waitForFunction(() => window.__livery, null, { timeout: 60_000 });
  await loaded('#app [data-livery-component="PromoBanner"]');
  await loaded('#app [data-livery-component="ShopQuantity"]');
  check((await colours('#app [data-livery-component="PromoBanner"]')).notWhite > 0.5, 'reload draws cached templates with catalog networking disabled');
  const memory = await page.evaluate(() => window.__livery.cachedComponents({ persistentCache: false }));
  check(memory === null || memory.length === 0, 'memory-only sessions cannot read the persistent cache');
  const changedTrust = await page.evaluate(() => window.__livery.cachedComponents({
    unsigned: false, keys: [{ keyId: 'different-trust', publicKey: btoa('x'.repeat(32)) }],
  }));
  check(changedTrust === null || changedTrust.length === 0, 'a signed session cannot reuse an unsigned cached manifest');

  await page.unrouteAll({ behavior: 'wait' });
  await page.evaluate(() => window.__livery.openPeer());
  const tab = await page.context().newPage();
  try {
    await tab.goto(`${base}?player=${player}&renderer=${process.env.LIVERY_RENDERER ?? 'lit'}`);
    await tab.waitForFunction(() => window.__livery, null, { timeout: 60_000 });
    const manifest = JSON.parse(await readFile(join(site, 'catalog/manifest.json'), 'utf8'));
    const persistedState = () => page.evaluate(() => new Promise((resolve, reject) => {
        const open = indexedDB.open('livery-v1', 1);
        open.onerror = () => reject(open.error);
        open.onsuccess = () => {
          const db = open.result;
          const request = db.transaction('entries').objectStore('entries').getAll();
          request.onsuccess = () => { db.close(); resolve(request.result.filter((v) => typeof v === 'string').map((v) => JSON.parse(v))); };
          request.onerror = () => { db.close(); reject(request.error); };
        };
      }));
    // The first response is held while another session tries to refresh the same namespace.
    // After it commits the higher serial, a queued older response must be rejected.
    for (const other of ['session', 'tab']) {
      let requests = 0, started, release;
      const firstStarted = new Promise((resolve) => { started = resolve; });
      const blocked = new Promise((resolve) => { release = resolve; });
      const higher = manifest.serial + 2;
      await page.context().route('**/catalog/manifest.json', async (route) => {
        const first = ++requests === 1;
        if (first) { started(); await blocked; }
        await route.fulfill({ json: { ...manifest, serial: first ? higher : higher - 1 } });
      });
      const first = page.evaluate(() => window.__livery.refresh());
      await firstStarted;
      const second = other === 'tab' ? tab.evaluate(() => window.__livery.refresh())
        : page.evaluate(() => window.__livery.refreshPeer());
      try {
        await page.waitForTimeout(150);
        check(requests === 1, `shared persistent refresh waits for the lock across ${other}s`);
      } finally { release(); }
      const results = await Promise.all([first, second]);
      check(results[0] === 'Updated' && results[1] === 'Failed', `older queued ${other} response cannot replace the accepted serial: ${results}`);
      const saved = await persistedState();
      check(Math.max(...saved.map((v) => v.acceptedSerial ?? 0)) === higher && Math.max(...saved.map((v) => v.serial ?? 0)) === higher,
        `persistent manifest and watermark retain the higher serial across ${other}s`);
      manifest.serial = higher;
      await page.context().unrouteAll({ behavior: 'wait' });
    }
    // The tab still memoized the original serial before either successful activation.
    // A later withdrawal with an unrelated broken download must preserve the latest disk snapshot.
    const candidate = { ...manifest, serial: manifest.serial + 1,
      designs: manifest.designs.map((d) => d.id === 'article-card' ? { ...d, revoked: true } : d),
      templates: manifest.templates.map((t) => t.designId === 'quantity-stepper' ? { ...t, sha256: '0'.repeat(64) } : t),
    };
    await page.context().route('**/catalog/manifest.json', (route) => route.fulfill({ json: candidate }));
    check(await tab.evaluate(() => window.__livery.refresh()) === 'Failed', 'unrelated missing replacement blob keeps activation pending');
    const saved = await persistedState();
    const active = saved.find((v) => v.serial === manifest.serial);
    check(active?.designs.find((d) => d.id === 'article-card')?.revoked === true,
      'stale tab applies revocations to the latest persisted playable snapshot');
    check(Math.max(...saved.map((v) => v.acceptedSerial ?? 0)) === candidate.serial,
      'pending replacement retains its accepted serial independently of activation');
  } finally { await tab.close(); await page.context().unrouteAll({ behavior: 'wait' }); }

} catch (e) {
  check(false, String(e));
  console.log(logs.join('\n'));
} finally {
  releaseDecision();
  await context.close();
}
}
await browser.close();
server.close();
process.exit(failed ? 1 : 0);
