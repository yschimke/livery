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
<style>body { font-family: sans-serif; width: 420px; } livery-promo-banner { --livery-a2ui-height: 180px; } livery-shop-quantity { --livery-a2ui-height: 120px; }</style>
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
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 480, height: 700 } });
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
  const banner = await colours('#app livery-promo-banner');
  check(banner.notWhite > 0.5 && banner.distinct > 8, `the banner draws its surface and text: ${JSON.stringify(banner)}`);

  // A real tap on the drawn "Shop now": the template's host action, through the gate, to the agent.
  const box = await (await page.$('#app livery-promo-banner')).boundingBox();
  await page.mouse.click(box.x + 70, box.y + 105);
  await page.waitForFunction(() => window.__livery.actions.length > 0, null, { timeout: 10_000 }).catch(() => {});
  const action = await page.evaluate(() => window.__livery.actions[0]);
  check(action?.name === 'startTrial' && action?.sourceComponentId === 'promo' && action?.context?.from === 'chat', `tapping the CTA sends the agent its event: ${JSON.stringify(action)}`);

  // A real tap on "+": the template's `set` writes the data model, and the same document takes the new value.
  const qtyBox = await (await page.$('#app livery-shop-quantity')).boundingBox();
  const before = await statsOf('#app livery-shop-quantity');
  await page.mouse.click(qtyBox.x + qtyBox.width - 34, qtyBox.y + 34);
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
  const secondBox = await (await page.$('#second livery-promo-banner')).boundingBox();
  await page.mouse.click(secondBox.x + 55, secondBox.y + 105);
  await page.waitForFunction(() => window.__livery.actions2.length > 0, null, { timeout: 10_000 }).catch(() => {});
  const second = await page.evaluate(() => window.__livery.actions2[0]);
  check(second?.name === 'secondTrial' && await page.evaluate(() => window.__livery.actions.length) === firstCount, `a second catalog's component reports to its own processor: ${JSON.stringify(second)}`);

  const problems = await page.evaluate(() => window.__livery.problems);
  check(problems.length === 0, `no problems reported: ${JSON.stringify(problems)}`);
} catch (e) {
  check(false, String(e));
  console.log(logs.join('\n'));
} finally {
  await browser.close();
  server.close();
}
process.exit(failed ? 1 : 0);
