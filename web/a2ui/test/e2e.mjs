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
rmSync(site, { recursive: true, force: true });
mkdirSync(site, { recursive: true });

// The sample catalog plus the two-way one, built by the livery CLI.
const src = join(site, 'catalog-src');
cpSync(join(repo, 'samples/catalog'), src, { recursive: true });
for (const d of ['contracts', 'designs']) cpSync(join(repo, 'samples/two-way', d), join(src, d), { recursive: true });
const cli = join(repo, 'livery-publish/build/install/livery/bin/livery');
if (!existsSync(cli)) throw new Error('run ./gradlew :livery-publish:installDist first');
execFileSync(cli, ['build', src, join(site, 'catalog')], { stdio: 'inherit' });

// This package's Wasm module beside the page.
const require = createRequire(import.meta.url);
cpSync(join(root, 'dist/livery-web'), join(site, 'livery-web'), { recursive: true });

// The page, bundled; @js-joda/core (imported by the Wasm module's glue) through an import map.
await build({ entryPoints: [join(here, 'app.ts')], bundle: true, format: 'esm', target: 'es2022', outfile: join(site, 'app.js'), external: ['./livery-web/*'], logLevel: 'warning' });
await build({ stdin: { contents: "export * from '@js-joda/core';", resolveDir: root }, bundle: true, format: 'esm', outfile: join(site, 'js-joda.mjs'), logLevel: 'warning' });
writeFileSync(join(site, 'index.html'), `<!doctype html><meta charset="utf-8">
<script type="importmap">{"imports": {"@js-joda/core": "./js-joda.mjs"}}</script>
<style>body { font-family: sans-serif; width: 420px; } livery-shop-quantity { --livery-a2ui-height: 120px; }</style>
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
const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
const page = await browser.newPage({ viewport: { width: 480, height: 700 } });
let decisionRequest;
let releaseDecision;
const decisionGate = new Promise((resolve) => { releaseDecision = resolve; });
await page.route('https://decisions.example/v1/decisions/query', async (route) => {
  if (route.request().method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: { 'access-control-allow-origin': '*', 'access-control-allow-methods': 'POST', 'access-control-allow-headers': 'content-type' } });
    return;
  }
  decisionRequest = route.request().postDataJSON();
  await decisionGate;
  await route.fulfill({ contentType: 'application/json', headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify({
    placement: 'assistant-offer', decisionId: 'server:browser', template: { designId: 'banner-classic' },
    data: { headline: 'Server offer', cta: 'Go' }, ttlSeconds: 60,
  }) });
});
const logs = [];
page.on('console', (m) => logs.push(`${m.type()}: ${m.text()}`));
page.on('pageerror', (e) => logs.push(`pageerror: ${e.message}`));
page.on('response', (r) => { if (r.status() >= 400) logs.push(`${r.status()} ${r.url()}`); });
let failed = 0;
const check = (ok, what) => { console.log(`${ok ? 'ok' : 'FAIL'} - ${what}`); if (!ok) failed++; };
try {
  await page.goto(base);
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
    const el = find(document);
    return el ? { ...el.liveryStats, iframes: el.shadowRoot.querySelectorAll('iframe').length, canvases: el.shadowRoot.querySelectorAll('canvas').length } : null;
  }, selector);
  const loaded = (selector) => page.waitForFunction((sel) => {
    const find = (root) => {
      const hit = root.querySelector(sel); if (hit) return hit;
      for (const el of root.querySelectorAll('*')) if (el.shadowRoot) { const f = find(el.shadowRoot); if (f) return f; }
      return null;
    };
    return (find(document)?.liveryStats?.loads ?? 0) > 0;
  }, selector, { timeout: 60_000 });
  await loaded('#app livery-promo-banner');
  await loaded('#app livery-shop-quantity');
  const promoStats = await statsOf('#app livery-promo-banner');
  check(promoStats.iframes === 0 && promoStats.canvases === 1, `drawn on a canvas in the page, no iframe: ${JSON.stringify(promoStats)}`);
  await page.waitForTimeout(300);
  if (out) { mkdirSync(out, { recursive: true }); await page.screenshot({ path: join(out, 'a2ui-web.png') }); }

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
  const banner = await colours('#app livery-promo-banner');
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
  await tap('#app livery-promo-banner');
  await page.waitForFunction(() => window.__livery.actions.length > 0, null, { timeout: 10_000 }).catch(() => {});
  const action = await page.evaluate(() => window.__livery.actions[0]);
  check(action?.name === 'startTrial' && action?.sourceComponentId === 'promo' && action?.context?.from === 'chat' && typeof action?.context?.route === 'string',
    `tapping the CTA sends the agent its event, with the agent's context and the template's declared route: ${JSON.stringify(action)}`);
  // An argument the contract doesn't declare never reaches the agent (delivered as the player would).
  await hostAction('#app livery-promo-banner', { name: 'cta', valueType: 'string', value: '{"route":"/premium","extra":"x"}' });
  await page.waitForFunction(() => window.__livery.actions.length > 1, null, { timeout: 10_000 }).catch(() => {});
  const undeclared = await page.evaluate(() => window.__livery.actions[1]);
  check(undeclared?.context?.route === '/premium' && !('extra' in (undeclared?.context ?? {})), `nothing undeclared reaches the agent: ${JSON.stringify(undeclared)}`);
  // Livery's own events say where the agent placed the template: a2ui:<surfaceId>/<componentId>.
  const events = await page.evaluate(() => window.__livery.events());
  const promo = events.filter((e) => e.designId && e.decisionId === 'a2ui:chat-1/promo');
  check(promo.some((e) => e.type === 'Impression') && promo.some((e) => e.type === 'Action' && e.action === 'cta'),
    `events carry the A2UI decision id: ${JSON.stringify(events.map((e) => [e.type, e.decisionId, e.action]))}`);

  // A real tap on "+": the template's `set` writes the data model, and the same document takes the new value.
  const before = await statsOf('#app livery-shop-quantity');
  await tap('#app livery-shop-quantity', 'right');
  await page.waitForFunction(() => window.__livery.data('/cart/quantity') === 2, null, { timeout: 10_000 }).catch(() => {});
  check(await page.evaluate(() => window.__livery.data('/cart/quantity')) === 2, 'tapping + wrote /cart/quantity = 2');
  await page.waitForTimeout(300);
  const after = await statsOf('#app livery-shop-quantity');
  check(after.loads === before.loads && after.sets > before.sets, `and the document stayed, its value set: ${JSON.stringify({ before, after })}`);

  // The agent changes the data: the banner's headline moves, in the same document.
  const promoBefore = await statsOf('#app livery-promo-banner');
  await page.evaluate(() => window.__livery.send([{ version: 'v0.9', updateDataModel: { surfaceId: 'chat-1', path: '/offer/title', value: 'Summer sale' } }]));
  await page.waitForFunction((n) => {
    const find = (root) => { const hit = root.querySelector('#app livery-promo-banner'); if (hit) return hit; for (const el of root.querySelectorAll('*')) if (el.shadowRoot) { const f = find(el.shadowRoot); if (f) return f; } return null; };
    return (find(document)?.liveryStats?.sets ?? 0) > n;
  }, promoBefore.sets, { timeout: 10_000 }).catch(() => {});
  await page.waitForTimeout(300);
  const promoAfter = await statsOf('#app livery-promo-banner');
  const redrawn = await colours('#app livery-promo-banner');
  check(promoAfter.loads === promoBefore.loads && promoAfter.sets > promoBefore.sets && redrawn.hash !== banner.hash,
    `a data-model update sets the headline on the playing document, which redraws: ${JSON.stringify({ promoBefore, promoAfter })}`);
  if (out) await page.screenshot({ path: join(out, 'a2ui-web-updated.png') });

  // A second catalog with the same component names draws with its own session, and its actions reach
  // its own processor, not the first one's.
  await loaded('#second livery-promo-banner');
  const firstCount = await page.evaluate(() => window.__livery.actions.length);
  await tap('#second livery-promo-banner');
  await page.waitForFunction(() => window.__livery.actions2.length > 0, null, { timeout: 10_000 }).catch(() => {});
  const second = await page.evaluate(() => window.__livery.actions2[0]);
  check(second?.name === 'secondTrial' && await page.evaluate(() => window.__livery.actions.length) === firstCount, `a second catalog's component reports to its own processor: ${JSON.stringify(second)}`);

  // The agent's createSurface theme colours its templates over the page's CSS theme; a surface
  // without one takes the page's.
  const themes = await page.evaluate(() => ({
    agent: document.querySelector('#app livery-shop-quantity')?.liveryTheme?.primary,
    page: document.querySelector('#second livery-promo-banner')?.liveryTheme?.primary,
  }));
  check(themes.agent === '#FF00897B' && typeof themes.page === 'string' && themes.page !== themes.agent, `a surface's primaryColor is its templates' primary: ${JSON.stringify(themes)}`);
  const shot = await (await page.$('#app livery-shop-quantity')).screenshot();
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
    banner: document.querySelector('#app livery-promo-banner').clientHeight,
    stepper: document.querySelector('#app livery-shop-quantity').clientHeight,
  }));
  check(heights.banner === 160 && heights.stepper === 120, `the banner takes its template's 160dp, the stepper the page's 120px: ${JSON.stringify(heights)}`);

  // The automatic banner remains visible during a delayed contract decision, then gets server data.
  const queryBefore = await statsOf('#app livery-promo-banner');
  const querySent = page.waitForRequest('https://decisions.example/v1/decisions/query');
  await page.evaluate(() => window.__livery.send([{ version: 'v0.9', updateComponents: { surfaceId: 'chat-1', components: [
    { id: 'promo', component: 'PromoBanner', headline: 'Waiting offer', cta: 'Wait', template: { query: { offer: 'summer' } } },
  ] } }]));
  await page.waitForFunction((n) => document.querySelector('#app livery-promo-banner')?.liveryStats?.sets > n, queryBefore.sets);
  await querySent;
  check(decisionRequest?.query?.contract === 'promo/banner@1' && decisionRequest?.params?.offer === 'summer' && decisionRequest?.context?.locale === 'en-GB',
    `query sends device context and agent params: ${JSON.stringify(decisionRequest)}`);
  const waiting = await statsOf('#app livery-promo-banner');
  check((await page.locator('#app livery-promo-banner canvas').count()) > 0, 'automatic canvas draws while the server waits');
  releaseDecision();
  await page.waitForFunction((n) => document.querySelector('#app livery-promo-banner')?.liveryStats?.sets > n, waiting.sets);
  await hostAction('#app livery-promo-banner', { name: 'cta', valueType: 'string', value: '{"route":"/sale"}' });
  const queryEvents = await page.evaluate(() => window.__livery.events());
  check(queryEvents.some((e) => e.type === 'Action' && e.placement === 'assistant-offer' && e.decisionId === 'a2ui:chat-1/promo'), 'decided actions retain the A2UI event id and server placement');

  const problems = await page.evaluate(() => window.__livery.problems);
  check(problems.length === 0, `no problems reported: ${JSON.stringify(problems)}`);
} catch (e) {
  check(false, String(e));
  console.log(logs.join('\n'));
} finally {
  releaseDecision();
  await browser.close();
  server.close();
}
process.exit(failed ? 1 : 0);
