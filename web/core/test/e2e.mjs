import { build } from 'esbuild';
import { cpSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { dirname, extname, join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { chromium } from 'playwright';
const here = dirname(fileURLToPath(import.meta.url)), root = resolve(here, '..'), repo = resolve(root, '../..');
const site = join(here, 'site');
rmSync(site, { recursive: true, force: true }); mkdirSync(site, { recursive: true });
execFileSync(join(repo, 'livery-publish/build/install/livery/bin/livery'), ['build', join(repo, 'samples/two-way'), join(site, 'catalog')], { stdio: 'inherit' });
cpSync(join(repo, 'samples/component-preview/preview.json'), join(site, 'preview.json'));
cpSync(join(root, 'dist/livery-web'), join(site, 'livery-web'), { recursive: true });
await build({ entryPoints: [join(here, 'app.ts')], bundle: true, format: 'esm', target: 'es2022', outfile: join(site, 'app.js') });
await build({ stdin: { contents: "export * from '@js-joda/core';", resolveDir: root }, bundle: true, format: 'esm', outfile: join(site, 'js-joda.mjs') });
writeFileSync(join(site, 'index.html'), `<!doctype html><html lang="en"><meta charset="utf-8"><title>Remote Compose component preview</title>
<script type="importmap">{"imports":{"@js-joda/core":"./js-joda.mjs"}}</script>
<style>body{font-family:system-ui;background:#f5f5fa;color:#242430;margin:24px}main{display:flex;gap:24px;flex-wrap:wrap}section{padding:20px;background:white;border:1px solid #ddd;border-radius:16px;max-width:500px}h1{font-size:24px}h2{font-size:18px}button,select{font:inherit}pre{font-size:12px;white-space:pre-wrap;max-width:440px}a{color:#6750a4}</style>
<h1>One component, independent previews</h1><p>Try the controls. Each preview keeps its own state.</p><main><section id="a"></section><section id="b"></section></main><script type="module" src="./app.js"></script></html>`);
const types = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.wasm': 'application/wasm', '.json': 'application/json', '.rc': 'application/octet-stream' };
let slowStarted = 0, slowAborted = 0;
const server = createServer(async (req, res) => {
  try {
    if (req.url === '/slow/manifest.json') {
      slowStarted++; res.on('close', () => { if (!res.writableFinished) slowAborted++; });
      await new Promise(r => setTimeout(r, 2000));
      if (!res.destroyed) { res.writeHead(200, { 'content-type': 'application/json' }); res.end(await readFile(join(site, 'catalog/manifest.json'))); }
      return;
    }
    const asked = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
    const file = resolve(site, '.' + (asked.endsWith('/') ? asked + 'index.html' : asked));
    if (!file.startsWith(site + sep)) { res.writeHead(403).end(); return; }
    const body = await readFile(file); res.writeHead(200, { 'content-type': types[extname(file)] ?? 'application/octet-stream' }); res.end(body);
  } catch { res.writeHead(404).end(); }
}).listen(Number(process.env.PORT ?? 0), '127.0.0.1');
await new Promise(r => server.once('listening', r));
const url = `http://127.0.0.1:${server.address().port}/`;
if (process.argv.includes('--serve')) { console.log(url); await new Promise(() => {}); }
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_EXECUTABLE_PATH });
const page = await browser.newPage({ viewport: { width: 1100, height: 850 } });
const errors = []; page.on('pageerror', e => errors.push(e.message));
let failed = 0;
const check = (ok, label) => { console.log(`${ok ? 'ok' : 'FAIL'} - ${label}`); if (!ok) failed++; };
try {
  await page.goto(url);
  await page.waitForFunction(() => window.previewReady, null, { timeout: 60000 });
  await page.waitForFunction(() => window.previews.every(p => p.state === 'ready'), null, { timeout: 30000 });
  check(await page.locator('canvas').count() === 2, 'two standalone canvases without A2UI');
  const before = await page.evaluate(() => window.previews[0].stats);
  const initialImage = await page.locator('#a canvas').screenshot();
  await page.locator('#a canvas').click({ position: { x: 326, y: 40 } });
  await page.waitForFunction(() => window.previews[0].values.quantity === 2);
  check(await page.evaluate(() => window.previews[1].values.quantity) === 1, 'actual RC tap changes only its local preview');
  check(await page.evaluate(n => window.previews[0].stats.loads === n, before.loads), 'data update retains document');
  await page.evaluate(() => window.previews[0].dispatch({ name: 'setQuantity', valueType: 'string', value: '10' }));
  check(await page.evaluate(() => window.previews[0].values.quantity === 2 && window.previews[0].actions.at(-1).route === 'dropped'), 'out-of-range action rejected by Kotlin gate');
  await page.evaluate(() => window.previews[0].dispatch({ name: 'bookmark', valueType: 'none', value: null }));
  check(await page.evaluate(() => window.previews[0].actions.at(-1).route) === 'dropped', 'undeclared action rejected');
  const beforeTheme = await page.locator('#a canvas').screenshot();
  await page.locator('#a select[aria-label="Theme"]').selectOption('Dark');
  await page.waitForTimeout(200);
  check(!(await page.locator('#a canvas').screenshot()).equals(beforeTheme), 'theme visibly redraws component');
  for (const width of ['280', '480']) {
    await page.locator('#a select[aria-label="Width"]').selectOption(width);
    await page.waitForFunction(w => document.querySelector('#a canvas').width === Number(w), width);
    check(await page.evaluate(n => window.previews[0].stats.loads > n, before.loads), `width ${width} reloads for correct player hit regions`);
    const quantity = await page.evaluate(() => window.previews[0].values.quantity);
    await page.locator('#a canvas').click({ position: { x: Number(width) - 34, y: 40 } });
    await page.waitForFunction(q => window.previews[0].values.quantity === q + 1, quantity);
    check(true, `resized ${width}px canvas still routes a real tap`);
  }
  await page.locator('#a button').click();
  await page.waitForFunction(() => window.previews[0].values.quantity === 1);
  check(await page.evaluate(() => window.previews[0].actions.length) === 0, 'reset restores fixture and clears log');
  await page.evaluate(() => window.previews[0].setData({ name: 'Invalid', quantity: 99 }));
  check(await page.evaluate(() => window.previews[0].state) === 'fallback', 'invalid data produces useful fallback');
  await page.evaluate(() => window.previews[0].reset());
  await page.waitForFunction(() => window.previews[0].state === 'ready');
  await page.locator('#a').evaluate(el => el.style.width = '160px');
  await page.waitForFunction(() => window.previews[0].state === 'fallback');
  check(await page.locator('#a canvas').count() === 0, 'narrow host uses actual width and falls back');
  await page.locator('#a').evaluate(el => el.style.width = '');
  await page.waitForFunction(() => window.previews[0].state === 'ready');
  const bad = await page.evaluate(async () => {
    const original = await (await fetch('./preview.json')).json();
    const results = [];
    for (const change of [p => p.schemaVersion = 9, p => p.template.sha256 = '0'.repeat(64), p => p.catalog.manifestUrl = 'https://untrusted.invalid/manifest.json']) {
      const p = structuredClone(original); change(p); const el = document.createElement('section'); document.body.append(el);
      const viewer = new window.ComponentPreview(el, new URL('./preview.json', location.href).href, { config: { unsigned: true }, fetch: async () => new Response(JSON.stringify(p)) });
      await viewer.ready; results.push(viewer.state); viewer.dispose(); el.remove();
    }
    const el = document.createElement('section'); document.body.append(el);
    const untrusted = new window.ComponentPreview(el, new URL('./preview.json', location.href).href, { config: {} });
    await untrusted.ready; results.push(untrusted.state); untrusted.dispose(); el.remove();
    return results;
  });
  check(bad.every(x => x === 'fallback'), `unsupported schema, pin mismatch, origin and trust fail closed: ${bad}`);
  const remoteHttp = await page.evaluate(async () => {
    let fetched = false; const el = document.createElement('section'); document.body.append(el);
    const p = new window.ComponentPreview(el, 'http://example.invalid/preview.json', { config: { unsigned: true }, fetch: async () => { fetched = true; return new Response('{}'); } });
    await p.ready; const result = !fetched && p.state === 'fallback'; p.dispose(); el.remove(); return result;
  });
  check(remoteHttp, 'remote plaintext descriptor rejected before fetch');
  const retained = await page.evaluate(() => window.previews[0].values.quantity);
  await page.locator('#a').evaluate(el => el.style.display = 'none');
  await page.waitForFunction(() => !document.querySelector('#a canvas'));
  await page.locator('#a').evaluate(el => el.style.display = '');
  await page.waitForFunction(() => !!document.querySelector('#a canvas') && window.previews[0].state === 'ready');
  check(await page.evaluate(() => window.previews[0].values.quantity) === retained, 'hidden player resumes with retained local values');
  await page.evaluate(async () => {
    const p = await (await fetch('./preview.json')).json(); p.catalog.manifestUrl = './slow/manifest.json';
    const el = document.createElement('section'); document.body.append(el);
    window.pendingPreview = new window.ComponentPreview(el, new URL('./preview.json', location.href).href, { config: { unsigned: true }, fetch: async () => new Response(JSON.stringify(p)) });
  });
  for (let i = 0; i < 50 && !slowStarted; i++) await new Promise(r => setTimeout(r, 20));
  await page.evaluate(() => window.pendingPreview.dispose());
  for (let i = 0; i < 50 && !slowAborted; i++) await new Promise(r => setTimeout(r, 20));
  check(slowStarted > 0 && slowAborted > 0, 'dispose cancels initial catalog fetch');
  check(await page.evaluate(() => window.pendingPreview.state === 'disposed' && !window.pendingPreview.element.querySelector('canvas')), 'pending completion cannot resurrect disposed viewer');
  await page.evaluate(() => window.pendingPreview.element.remove());
  const out = process.env.LIVERY_RENDER_OUT;
  if (out) { mkdirSync(out, { recursive: true }); await page.screenshot({ path: join(out, 'standalone-preview.png') }); }
  await page.evaluate(() => { window.previews[0].dispose(); window.previews[0].dispose(); });
  check(await page.locator('#a canvas').count() === 0 && await page.evaluate(() => window.previews[0].state) === 'disposed', 'dispose is idempotent and removes player');
  await page.locator('#b canvas').click({ position: { x: 326, y: 40 } });
  await page.waitForFunction(() => window.previews[1].values.quantity === 2);
  check(true, 'other session still works after disposal');
  check(errors.length === 0, `no browser errors: ${errors.join('; ')}`);
} catch (error) { check(false, String(error)); console.log(errors); }
finally { await browser.close(); server.close(); }
process.exitCode = failed ? 1 : 0;
