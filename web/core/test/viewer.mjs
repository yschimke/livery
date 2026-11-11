import assert from 'node:assert/strict';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { dirname, extname, join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { chromium } from 'playwright';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..'), repo = resolve(root, '../..');
const temporary = mkdtempSync(join(tmpdir(), 'livery-viewer-')), assets = join(temporary, 'viewer');
const built = JSON.parse(readFileSync(join(root, 'viewer-dist/bundle.json')));
execFileSync('unzip', ['-q', join(root, 'build', `livery-preview-viewer-${built.version}.zip`), '-d', assets]);
const publication = '/p/quantity-stepper/r/fixture-1/';
const manifest = JSON.parse(readFileSync(join(assets, 'bundle.json')));
for (const [path, spec] of Object.entries(manifest.files)) {
  const bytes = readFileSync(join(assets, path));
  assert.equal(bytes.length, spec.bytes); assert.equal(createHash('sha256').update(bytes).digest('hex'), spec.sha256);
}
execFileSync(process.env.LIVERY_CLI ?? join(repo, 'livery-publish/build/install/livery/bin/livery'), ['build', join(repo, 'livery-capture-cmp/build/samples/two-way'), join(temporary, 'catalog')], { stdio: 'inherit' });
cpSync(join(repo, 'samples/component-preview/preview.json'), join(temporary, 'preview.json'));
// Unknown descriptor metadata is allowed. Keep its Unicode character count below
// 64 KiB while testing the exact UTF-8 byte boundary, without changing bound data.
const byteCases = JSON.parse(readFileSync(join(repo, 'samples/component-preview/publication-cases.json'))).cases.filter(c => c.field === 'descriptorBytes');
assert.equal(byteCases.length, 2);
const descriptorCase = c => {
  const value = { ...JSON.parse(readFileSync(join(temporary, 'preview.json'))), notes: c.text.repeat(c.repeat) };
  const json = JSON.stringify(value);
  return json + ' '.repeat(c.bytes - Buffer.byteLength(json));
};
const acceptedCase = byteCases.find(c => c.accepted), rejectedCase = byteCases.find(c => !c.accepted);
const atLimit = descriptorCase(acceptedCase), oversized = descriptorCase(rejectedCase);
assert.equal(Buffer.byteLength(atLimit), acceptedCase.bytes);
assert(oversized.length < acceptedCase.bytes && Buffer.byteLength(oversized) === rejectedCase.bytes);
writeFileSync(join(temporary, 'limit.json'), atLimit); writeFileSync(join(temporary, 'oversized.json'), oversized);
const html = (mode, config = { unsigned: true }, descriptor = publication + 'preview.json') => `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Quantity stepper · Component preview</title>
<link rel="canonical" href="${publication}"><link rel="alternate" type="application/vnd.livery.preview+json" href="${descriptor}"><link rel="stylesheet" href="/assets/livery-preview/viewer.css">
<style>body{margin:0;padding:32px 24px;background:#f6f4f8;color:#26212e;font-family:system-ui}main{max-width:560px;margin:40px auto}h1{font-size:36px;letter-spacing:-.04em;margin:8px 0 12px}header p{color:#6b6276;max-width:440px;line-height:1.6}.eyebrow{font-size:12px;letter-spacing:.13em;text-transform:uppercase}main>p{font-size:13px;color:#6b6276}</style>
<main class="livery-publication-page"><header id="fallback" data-livery-fallback><div class="eyebrow">Remote Compose · Live preview</div><h1>Try the quantity stepper.</h1><p>This published component is interactive. Your changes stay in this preview, and everyone opens the same starting point.</p></header>
<div data-livery-preview data-mode="${mode}" data-descriptor="${descriptor}" data-canonical="${publication}" data-config='${JSON.stringify(config)}'></div><p>Revision fixture-1 · <a href="${publication}preview.json">Preview descriptor</a></p></main>
<script type="module" src="/assets/livery-preview/viewer.js"></script></html>`;
const types = { '.js': 'text/javascript', '.mjs': 'text/javascript', '.wasm': 'application/wasm', '.css': 'text/css', '.json': 'application/json', '.rc': 'application/octet-stream' };
const requests = [];
const server = createServer(async (req, res) => {
  requests.push({ url: req.url, method: req.method });
  try {
    const url = new URL(req.url, 'http://localhost');
    const mode = url.pathname.endsWith('/embed/') ? 'embed' : 'page';
    if ([publication, publication + 'embed/'].includes(url.pathname)) {
      const config = url.searchParams.has('no-trust') ? {} : { unsigned: true };
      const descriptor = publication + (url.searchParams.has('missing') ? 'missing.json' : url.searchParams.get('size') === 'limit' ? 'limit.json' : url.searchParams.get('size') === 'oversized' ? 'oversized.json' : 'preview.json');
      res.writeHead(200, { 'content-type': 'text/html', 'content-security-policy': "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; object-src 'none'" });
      res.end(html(mode, config, descriptor)); return;
    }
    const isAsset = url.pathname.startsWith('/assets/livery-preview/');
    if (!isAsset && !url.pathname.startsWith(publication)) throw new Error('missing');
    const directory = isAsset ? assets : temporary;
    const relative = decodeURIComponent(url.pathname.slice(isAsset ? '/assets/livery-preview/'.length : publication.length));
    const path = resolve(directory, relative);
    if (!path.startsWith(directory + sep)) throw new Error('path');
    const bytes = await readFile(path); res.writeHead(200, { 'content-type': types[extname(path)] ?? 'text/plain' }); res.end(bytes);
  } catch { res.writeHead(404).end(); }
}).listen(0, '127.0.0.1');
await new Promise(r => server.once('listening', r));
const base = `http://127.0.0.1:${server.address().port}`;
if (process.argv.includes('--serve')) { console.log(base + publication); await new Promise(() => {}); }
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_EXECUTABLE_PATH });
const context = await browser.newContext({ viewport: { width: 900, height: 840 } });
const page = await context.newPage(), errors = [], external = [];
page.on('pageerror', e => errors.push(e.message)); page.on('request', r => { if (!r.url().startsWith(base)) external.push(r.url()); });
let count = 0;
const check = (value, message) => { assert(value, message); count++; console.log(`ok - ${message}`); };
try {
  await page.addInitScript(() => {
    window.shareCalls = []; window.copyCalls = [];
    Object.defineProperty(navigator, 'share', { configurable: true, value: async data => { window.shareCalls.push({ data, active: navigator.userActivation.isActive }); if (window.cancelShare) throw new DOMException('Cancelled', 'AbortError'); } });
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async text => { window.copyCalls.push({ text, active: navigator.userActivation.isActive }); if (window.denyCopy) throw new DOMException('Denied', 'NotAllowedError'); } } });
  });
  await page.goto(base + publication);
  await page.locator('[data-state="ready"]').waitFor();
  await page.evaluate(async () => { window.handle = (await import('/assets/livery-preview/viewer.js')).mountPublication(document.querySelector('[data-livery-preview]')); });
  check(await page.locator('canvas').count() === 1, 'distributed bundle renders actual RC canvas under a revision URL');
  check(await page.evaluate(() => window.shareCalls.length === 0 && window.copyCalls.length === 0), 'no sharing happens before a user gesture');
  if (process.env.LIVERY_RENDER_OUT) { mkdirSync(process.env.LIVERY_RENDER_OUT, { recursive: true }); await page.waitForTimeout(200); await page.screenshot({ path: join(process.env.LIVERY_RENDER_OUT, 'publication-viewer.png'), fullPage: true }); }
  const image = await page.locator('canvas').screenshot();
  await page.locator('canvas').click({ position: { x: 326, y: 40 } });
  await page.waitForFunction(() => window.handle.preview.values.quantity === 2);
  check(!(await page.locator('canvas').screenshot()).equals(image), 'real canvas tap validates local action and visibly redraws');
  check((await page.getByRole('link', { name: 'Share to Bluesky' }).getAttribute('href')) === `https://bsky.app/intent/compose?${new URLSearchParams({ text: base + publication })}`, 'Bluesky intent shares canonical revision only');
  await context.route('https://bsky.app/intent/compose**', route => route.fulfill({ contentType: 'text/html', body: '<title>Compose intent</title>' }));
  const [composer] = await Promise.all([page.waitForEvent('popup'), page.getByRole('link', { name: 'Share to Bluesky' }).click()]);
  await composer.waitForLoadState();
  check(new URL(composer.url()).searchParams.get('text') === base + publication, 'fresh Bluesky click opens an ordinary composer with the revision URL');
  await composer.close();
  await page.getByRole('button', { name: 'Share…', exact: true }).click();
  check(await page.evaluate(url => window.shareCalls.length === 1 && window.shareCalls[0].active && window.shareCalls[0].data.url === url, base + publication), 'system share receives fresh activation and canonical URL');
  await page.evaluate(() => { window.cancelShare = true; }); await page.getByRole('button', { name: 'Share…', exact: true }).click();
  await page.getByText('Share cancelled', { exact: true }).waitFor(); check(true, 'cancelled system sharing does not post or redirect');
  await page.getByRole('button', { name: 'Copy link', exact: true }).click();
  check(await page.evaluate(url => window.copyCalls[0].active && window.copyCalls[0].text === url, base + publication), 'copy link receives fresh activation and ignores local value edits');
  await page.evaluate(() => { window.denyCopy = true; }); await page.getByRole('button', { name: 'Copy link', exact: true }).click();
  check(await page.getByRole('textbox', { name: 'Preview link' }).inputValue() === base + publication, 'clipboard denial offers selectable canonical link');
  await page.getByRole('button', { name: 'Reset', exact: true }).click(); await page.waitForFunction(() => window.handle.preview.values.quantity === 1);
  check(true, 'reset restores publication data');
  if (process.env.LIVERY_RENDER_OUT) { mkdirSync(process.env.LIVERY_RENDER_OUT, { recursive: true }); await page.screenshot({ path: join(process.env.LIVERY_RENDER_OUT, 'publication-clipboard-fallback.png'), fullPage: true }); }
  await page.goto(base + publication + 'embed/'); await page.locator('[data-state="ready"]').waitFor();
  check(await page.getByLabel('Width', { exact: true }).count() === 0 && await page.getByRole('button', { name: 'Reset', exact: true }).count() === 1, 'embed keeps minimal theme/reset controls');
  check(await page.getByRole('link', { name: 'Open full preview' }).getAttribute('href') === base + publication, 'embed points to canonical page, not iframe URL');
  check(await page.getByRole('button', { name: 'Copy link' }).count() === 0, 'embed omits full-page sharing toolbar');
  check(await page.locator('#fallback').isHidden() && (await page.locator('canvas').boundingBox()).y < 300, 'ready embed keeps live component visible above the fold');
  await page.goto(base + publication + '?no-trust'); await page.locator('[data-state="fallback"]').waitFor();
  check(await page.locator('canvas').count() === 0 && await page.locator('#fallback').isVisible(), 'missing operator trust fails closed with server fallback intact');
  await page.goto(base + publication + '?missing'); await page.locator('[data-state="fallback"]').waitFor();
  check(await page.locator('#fallback').isVisible(), 'missing descriptor leaves readable server fallback');
  await page.goto(base + publication + '?size=limit'); await page.locator('[data-state="ready"]').waitFor();
  check(await page.locator('canvas').count() === 1, 'exactly 64 KiB of UTF-8 descriptor bytes remains playable');
  const requestStart = requests.length;
  await page.goto(base + publication + '?size=oversized'); await page.locator('[data-state="fallback"]').waitFor();
  check((await page.locator('[data-state="fallback"]').innerText()).includes('Preview descriptor is too large') &&
    await page.locator('canvas').count() === 0 && !requests.slice(requestStart).some(r => r.url.includes('/catalog/')),
    'Unicode descriptor below character limit but above 64 KiB bytes is refused before catalog fetch');
  const noJs = await browser.newContext({ javaScriptEnabled: false }); const staticPage = await noJs.newPage(); await staticPage.goto(base + publication);
  check(await staticPage.locator('#fallback').isVisible() && await staticPage.getByRole('link', { name: 'Preview descriptor' }).count() === 1, 'no-JavaScript page retains text and discovery link'); await noJs.close();
  const unsupported = await context.newPage(); unsupported.on('pageerror', e => errors.push(e.message));
  await unsupported.addInitScript(() => { Object.defineProperty(navigator, 'share', { value: undefined }); Object.defineProperty(navigator, 'clipboard', { value: undefined }); });
  await unsupported.goto(base + publication); await unsupported.locator('[data-state="ready"]').waitFor();
  check(await unsupported.getByRole('button', { name: 'Share…', exact: true }).count() === 0, 'unsupported system sharing leaves Bluesky and copy available');
  await unsupported.getByRole('button', { name: 'Copy link', exact: true }).click();
  check(await unsupported.getByRole('textbox', { name: 'Preview link' }).isVisible(), 'unsupported clipboard retains manual copy fallback');
  await unsupported.close();
  check(external.length === 0, `runtime loads only same-origin assets (${external.length} external requests)`);
  check(requests.every(r => r.method === 'GET'), 'local interaction and sharing perform no server writes');
  check(errors.length === 0, `no browser errors: ${errors.join(', ')}`);
  console.log(`${count} distributed viewer assertions passed`);
} finally { await browser.close(); await new Promise(r => server.close(r)); rmSync(temporary, { recursive: true, force: true }); }
