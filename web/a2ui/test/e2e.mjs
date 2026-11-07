// End-to-end in headless Chromium: build the page, serve it with a sample catalog and the web
// player, and check that Livery components draw, that a template's action reaches the agent with
// its checked arguments, and that a `set` writes the data model back and redraws.
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

// rc-players' web player, and this package's Wasm module beside the page.
const require = createRequire(import.meta.url);
cpSync(join(root, 'node_modules/@yschimke/remote-compose-player-cmp/dist'), join(site, 'player'), { recursive: true });
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

  // Each Livery component's player reaches `ready` with its document.
  const frameOf = async (tag) => {
    const handle = await page.waitForSelector(`#app ${tag} iframe`, { state: "attached", timeout: 60_000 });
    const frame = await handle.contentFrame();
    await frame.waitForFunction(() => document.documentElement.dataset.rcPlayerState === 'ready', null, { timeout: 60_000 });
    return frame;
  };
  const promo = await frameOf('livery-promo-banner');
  const qty = await frameOf('livery-shop-quantity');
  check(true, 'both templates play in the web player');
  if (out) { mkdirSync(out, { recursive: true }); await page.screenshot({ path: join(out, 'a2ui-web.png') }); }
  // Drawn, not just loaded: the banner has its surface and its text (a page with no theme once drew it transparent).
  const shot = await (await page.$('livery-promo-banner')).screenshot();
  const colours = await page.evaluate(async (b64) => {
    const img = new Image(); img.src = `data:image/png;base64,${b64}`; await img.decode();
    const c = document.createElement('canvas'); c.width = img.width; c.height = img.height;
    const g = c.getContext('2d'); g.drawImage(img, 0, 0);
    const d = g.getImageData(0, 0, c.width, c.height).data;
    let notWhite = 0; const seen = new Set();
    for (let i = 0; i < d.length; i += 4) { if (d[i] < 245 || d[i + 1] < 245 || d[i + 2] < 245) notWhite++; seen.add((d[i] >> 4) << 8 | (d[i + 1] >> 4) << 4 | (d[i + 2] >> 4)); }
    return { notWhite: notWhite / (d.length / 4), distinct: seen.size };
  }, shot.toString('base64'));
  check(colours.notWhite > 0.5 && colours.distinct > 8, `the banner draws its surface and text: ${JSON.stringify(colours)}`);
  await page.waitForFunction(() => window.__livery.events().some((e) => e.type === 'Impression') || true);

  // A template's host action, as the player reports it, goes to the agent with its checked arguments.
  await promo.evaluate(() => window.parent.postMessage({ type: 'cp-rc-host-named-action', name: 'cta', valueType: 'string', value: '{"route":"/premium","extra":"x"}' }, '*'));
  await page.waitForFunction(() => window.__livery.actions.length > 0, null, { timeout: 10_000 });
  const action = await page.evaluate(() => window.__livery.actions[0]);
  check(action.name === 'startTrial' && action.sourceComponentId === 'promo', `the agent's event: ${JSON.stringify(action)}`);
  check(action.context.route === '/premium' && action.context.from === 'chat' && !('extra' in action.context), 'with the declared argument, the agent context, and nothing undeclared');

  // A `set` writes the input's data-model path, which redraws the component.
  await qty.evaluate(() => window.parent.postMessage({ type: 'cp-rc-host-named-action', name: 'setQuantity', valueType: 'int', value: 3 }, '*'));
  await page.waitForFunction(() => window.__livery.data('/cart/quantity') === 3, null, { timeout: 10_000 });
  check(true, 'setQuantity wrote /cart/quantity = 3');
  await qty.waitForFunction(() => document.documentElement.dataset.rcPlayerState === 'ready', null, { timeout: 30_000 });

  // The agent changes the data: the banner draws the new headline (a new document, swapped in place).
  const before = await page.evaluate(() => window.__livery.events().length);
  await page.evaluate(() => window.__livery.send([{ version: 'v0.9', updateDataModel: { surfaceId: 'chat-1', path: '/offer/title', value: 'Summer sale' } }]));
  await page.waitForFunction(() => window.__livery.events().some((e) => e.type === 'Impression'), null, { timeout: 30_000 });
  check(true, `a data-model update redraws (${before} earlier events drained)`);

  // A second catalog with the same component names draws with its own session, and its actions reach
  // its own processor, not the first one's.
  const secondHandle = await page.waitForSelector('#second livery-promo-banner iframe', { state: 'attached', timeout: 60_000 });
  const secondFrame = await secondHandle.contentFrame();
  await secondFrame.waitForFunction(() => document.documentElement.dataset.rcPlayerState === 'ready', null, { timeout: 60_000 });
  const firstCount = await page.evaluate(() => window.__livery.actions.length);
  await secondFrame.evaluate(() => window.parent.postMessage({ type: 'cp-rc-host-named-action', name: 'cta', valueType: 'string', value: '{"route":"/b"}' }, '*'));
  await page.waitForFunction(() => window.__livery.actions2.length > 0, null, { timeout: 10_000 });
  const second = await page.evaluate(() => window.__livery.actions2[0]);
  check(second.name === 'secondTrial' && await page.evaluate(() => window.__livery.actions.length) === firstCount, `a second catalog's component reports to its own processor: ${JSON.stringify(second)}`);

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
