// Real Dokka output + real hosted RC viewer. Build/install both independently first.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdirSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, extname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
const here = dirname(fileURLToPath(import.meta.url));
const { chromium } = createRequire(join(here, '../../web/core/package.json'))('playwright');
const origin = process.env.LIVERY_PUBLICATION_ORIGIN;
if (!origin) throw new Error('Set LIVERY_PUBLICATION_ORIGIN to the loopback publication server origin');
const docs = join(here, 'build/dokka');
execFileSync('python3', [join(here, 'embed.py'), '--input', join(docs, 'html'), '--output', join(docs, 'browser'), '--mapping', join(here, 'previews.json'), '--documentation-version', 'fixture-1', '--development-origin', origin], { stdio: 'inherit' });
function htmlFiles(path) {
  return readdirSync(path, { withFileTypes: true }).flatMap(e => e.isDirectory() ? htmlFiles(join(path, e.name)) : e.name.endsWith('.html') ? [join(path, e.name)] : []);
}
const pages = htmlFiles(join(docs, 'browser')).filter(p => readFileSync(p, 'utf8').includes('class="rc-preview-embed"'));
assert.equal(pages.length, 1, 'exactly one real generated API page contains both mapped links');
const apiPath = relative(join(docs, 'browser'), pages[0]).split(sep).join('/');
const types = { '.html': 'text/html', '.css': 'text/css', '.js': 'text/javascript', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.woff': 'font/woff', '.woff2': 'font/woff2' };
const server = createServer((req, res) => {
  try {
    const file = resolve(docs, '.' + decodeURIComponent(new URL(req.url, 'http://localhost').pathname));
    if (!file.startsWith(docs + sep) || !statSync(file).isFile()) { res.writeHead(404).end(); return; }
    res.setHeader('Content-Type', types[extname(file)] ?? 'application/octet-stream');
    res.end(readFileSync(file));
  } catch { res.writeHead(404).end(); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${server.address().port}`;
let browser;
let assertions = 0;
function check(value, message) { assert(value, message); assertions++; console.log(`ok - ${message}`); }
try {
  browser = await chromium.launch({ executablePath: process.env.CHROMIUM_EXECUTABLE_PATH });
  const context = await browser.newContext({ viewport: { width: 1280, height: 1800 } });
  const page = await context.newPage();
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await page.goto(`${base}/html/${apiPath}`);
  const original = await page.locator('body').innerText();
  check(await page.locator('iframe').count() === 0 && await page.locator('a[href="https://preview.coo.ee/p/quantity-stepper/r/fixture-1/"]').count() === 2, 'unmodified Dokka retains two ordinary pinned KDoc links');
  check(original.includes('quantityStepperInputs()') && original.includes('println'), 'Dokka resolves @sample into actual Kotlin source');
  await page.goto(`${base}/browser/${apiPath}`);
  const embeds = page.locator('.rc-preview-embed');
  check(await embeds.count() === 2, 'adapter adds two previews to the real API page');
  const frames = [];
  for (const iframe of await embeds.locator('iframe').all()) {
    check(!!await iframe.getAttribute('title') && await iframe.getAttribute('loading') === 'lazy', 'iframe has an accessible title and lazy loading');
    const frame = await (await iframe.elementHandle()).contentFrame();
    await frame.locator('[data-state="ready"]').waitFor({ timeout: 60000 });
    await frame.evaluate(async () => {
      const script = document.querySelector('script[src$="viewer.js"]');
      window.docsViewer = (await import(script.src)).mountPublication(document.querySelector('[data-livery-preview]'));
    });
    frames.push(frame);
  }
  check((await Promise.all(frames.map(f => f.evaluate(() => window.docsViewer.preview.values.quantity)))).every(n => n === 1), 'both real RC players start from published quantity 1');
  const canvas = frames[0].locator('canvas');
  const before = await canvas.screenshot();
  const box = await canvas.boundingBox();
  await canvas.click({ position: { x: box.width - 34, y: 40 } });
  await frames[0].waitForFunction(() => window.docsViewer.preview.values.quantity === 2);
  await page.waitForTimeout(150);
  check(!(await canvas.screenshot()).equals(before), 'an actual canvas action visibly changes the first preview');
  check(await frames[1].evaluate(() => window.docsViewer.preview.values.quantity) === 1, 'the second embed keeps independent local state');
  await frames[0].getByRole('button', { name: 'Reset', exact: true }).click();
  await frames[0].waitForFunction(() => window.docsViewer.preview.values.quantity === 1);
  check(true, 'embedded reset restores published input');
  const link = page.getByRole('link', { name: 'Open interactive preview', exact: true }).first();
  await link.focus();
  check(await link.evaluate(el => document.activeElement === el), 'visible fallback link supports keyboard focus outside the canvas');
  check((await page.locator('body').innerText()).includes('println'), 'source sample remains visible after embedding');
  if (process.env.LIVERY_RENDER_OUT) {
    mkdirSync(process.env.LIVERY_RENDER_OUT, { recursive: true });
    await page.screenshot({ path: join(process.env.LIVERY_RENDER_OUT, 'dokka-preview.png'), fullPage: true });
  }
  check(errors.length === 0, `normal generated page has no browser errors: ${errors.join(', ')}`);
  const unavailable = await context.newPage();
  const embedUrl = `${new URL(origin).origin}/p/quantity-stepper/r/fixture-1/embed/`;
  await unavailable.route(embedUrl, route => route.fulfill({ status: 503, contentType: 'text/html', body: 'Preview temporarily unavailable' }));
  await unavailable.goto(`${base}/browser/${apiPath}`);
  await unavailable.frameLocator('.rc-preview-embed iframe').first().getByText('Preview temporarily unavailable').waitFor();
  check(await unavailable.getByRole('link', { name: 'Open interactive preview', exact: true }).count() === 2 && await unavailable.getByRole('link', { name: 'Open interactive preview', exact: true }).first().isVisible(), 'unavailable iframe retains visible external fallback links');
  const noJs = await browser.newContext({ javaScriptEnabled: false, viewport: { width: 1280, height: 2400 } });
  const staticPage = await noJs.newPage();
  await staticPage.goto(`${base}/browser/${apiPath}`);
  await staticPage.locator('.rc-preview-fallback img').first().waitFor({ state: 'visible' });
  check(await staticPage.locator('.rc-preview-fallback img').first().evaluate(img => img.complete && img.naturalWidth > 0), 'static publication thumbnail loads without JavaScript');
  check(await staticPage.getByRole('link', { name: 'Open interactive preview', exact: true }).first().isVisible() && await staticPage.locator('.rc-preview-fallback img').count() === 2, 'JavaScript-disabled documentation retains links and thumbnails');
  await noJs.close(); await context.close();
  console.log(`${assertions} generated Dokka + hosted viewer assertions passed`);
} finally {
  if (browser) await browser.close();
  await new Promise(resolve => server.close(resolve));
}
