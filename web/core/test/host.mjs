// Optional integration smoke against a running publication host and the actual viewer assets.
// The host and installed fixture are prepared independently; this script builds nothing.
import assert from 'node:assert/strict';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { chromium } from 'playwright';
const url = process.env.LIVERY_PUBLICATION_URL;
if (!url) throw new Error('Set LIVERY_PUBLICATION_URL to a running fixture publication page');
const canonical = new URL(url).href;
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_EXECUTABLE_PATH });
const errors = [];
let assertions = 0;
function check(value, message) { assert(value, message); assertions++; console.log(`ok - ${message}`); }
try {
  for (const [label, width] of [['desktop', 900], ['mobile', 390]]) {
    const context = await browser.newContext({ viewport: { width, height: 900 } });
    const page = await context.newPage(); page.on('pageerror', e => errors.push(e.message));
    const response = await page.goto(canonical); check(response.status() === 200, `${label}: server publication HTML loads`);
    await page.locator('[data-state="ready"]').waitFor();
    const info = await page.evaluate(async () => {
      const element = document.querySelector('[data-livery-preview]');
      const script = document.querySelector('script[src$="viewer.js"]');
      window.hostViewer = (await import(script.src)).mountPublication(element);
      return {
        canonical: document.querySelector('link[rel="canonical"]')?.href,
        discovery: document.querySelector('link[rel="alternate"][type="application/vnd.livery.preview+json"]')?.href,
        og: document.querySelector('meta[property="og:image"]')?.content,
        quantity: window.hostViewer.preview.values.quantity,
      };
    });
    check(info.canonical === canonical && info.discovery === new URL('preview.json', canonical).href, `${label}: SSR canonical/discovery match the immutable revision`);
    check(!!info.og && (await context.request.get(info.og)).ok(), `${label}: SSR thumbnail is fetchable`);
    check(info.quantity === 1, `${label}: a new viewer starts from publication data`);
    const canvas = page.locator('canvas'); const box = await canvas.boundingBox();
    check(box.width <= width, `${label}: real canvas fits viewport`);
    await canvas.click({ position: { x: box.width - 34, y: 40 } });
    await page.waitForFunction(() => window.hostViewer.preview.values.quantity === 2);
    check(true, `${label}: actual RC tap updates only local quantity`);
    check(new URL(await page.getByRole('link', { name: 'Share to Bluesky' }).getAttribute('href')).searchParams.get('text') === canonical, `${label}: share intent keeps the canonical revision`);
    const descriptor = await (await context.request.get(info.discovery)).json();
    check(descriptor.data.quantity === 1, `${label}: interaction did not mutate published data`);
    if (process.env.LIVERY_RENDER_OUT) { mkdirSync(process.env.LIVERY_RENDER_OUT, { recursive: true }); await page.waitForTimeout(200); await page.screenshot({ path: join(process.env.LIVERY_RENDER_OUT, `host-${label}.png`), fullPage: true }); }
    await page.goto(new URL('embed/', canonical).href); await page.locator('[data-state="ready"]').waitFor();
    check(await page.getByLabel('Width', { exact: true }).count() === 0 && await page.getByRole('link', { name: 'Open full preview' }).getAttribute('href') === canonical, `${label}: real embed uses minimal controls and page canonical`);
    check(await page.locator('[data-livery-fallback]').isHidden() && (await page.locator('canvas').boundingBox()).y < 300, `${label}: ready embed hides static fallback and keeps controls visible`);
    if (process.env.LIVERY_RENDER_OUT) { await page.waitForTimeout(200); await page.screenshot({ path: join(process.env.LIVERY_RENDER_OUT, `host-embed-${label}.png`), fullPage: true }); }
    await context.close();
  }
  const context = await browser.newContext({ javaScriptEnabled: false }); const page = await context.newPage(); await page.goto(canonical);
  check((await page.locator('body').innerText()).includes('Quantity stepper') && await page.locator('canvas').count() === 0, 'real no-JavaScript SSR retains human-readable fallback');
  if (process.env.LIVERY_RENDER_OUT) await page.screenshot({ path: join(process.env.LIVERY_RENDER_OUT, 'host-nojs.png'), fullPage: true });
  await context.close();
  check(errors.length === 0, `host smoke has no browser errors: ${errors.join(', ')}`);
  console.log(`${assertions} host integration assertions passed`);
} finally { await browser.close(); }
