// Drives a Perfetto UI to a zoomed view of the benchmark host's tracks and screenshots it.
// Run by screenshots.py, not by hand:  node capture.js <config.json>
// The config is a list of {baseUrl, uiVersion, trace, visStart, visEnd, out, width, height, renderTid, markers[{ts,text,color}]}.
// Needs playwright-core (NODE_PATH) and a Chromium (CHROME).
const { chromium } = require('playwright-core');
const fs = require('fs');
const configs = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROME, headless: true, args: ['--no-sandbox'] });
  for (const cfg of configs) {
    const { baseUrl, uiVersion, trace, visStart, visEnd, out, markers = [], renderTid = null } = cfg;
    const page = await (await browser.newContext({ viewport: { width: cfg.width || 1600, height: cfg.height || 1250 }, deviceScaleFactor: 2 })).newPage();
    await page.goto(`${baseUrl}/ui/${uiVersion}/#!/?url=${baseUrl}/traces/${trace}`);
    await page.waitForFunction(() => window.app && app.trace && app.trace.timeline && app.trace.traceInfo && app.trace.workspaces.currentWorkspace.tracks.children.length > 20, null, { timeout: 120000 });
    await page.waitForTimeout(2500);
    const picked = await page.evaluate(async ([a, b, marks, rtid]) => {
      // The cookie banner is not part of the picture.
      document.querySelectorAll('*').forEach((e) => { if (e.children.length < 6 && /uses cookies from Google/.test(e.textContent || '') && e.textContent.length < 400) e.closest('div')?.remove(); });
      marks.forEach((m) => app.trace.notes.addNote({ timestamp: BigInt(m.ts), color: m.color, text: m.text }));
      const t = app.trace.timeline;
      t.setVisibleWindow(t.visibleWindow.constructor.fromTime(BigInt(a), BigInt(b)));
      const root = app.trace.workspaces.currentWorkspace.tracks;
      const all = []; (function walk(n) { all.push(n); (n.children || []).forEach(walk); })(root);
      const find = (re) => all.find((n) => re.test(n.name || ''));
      const proc = find(/^ee\.schimke\.livery\.baselineprofile\.host \d+$/);
      // A thread has a scheduler-state track and a slice track of the same name; the slice track's uri is /slice_N.
      const leaf = (g) => (g && g.children && g.children.find((c) => c.name === g.name && (c.uri || '').startsWith('/slice_'))) || g;
      const picks = {
        launching: find(/^launching: ee\.schimke\.livery\.baselineprofile\.host$/),
        frames: proc && proc.children.find((c) => c.name === 'Actual Timeline'),
        main: leaf(proc && proc.children.find((c) => /^ineprofile\.host \d+$/.test(c.name))),
        // The process has several RenderThreads; the one that draws is the one holding DrawFrames.
        render: leaf(proc && proc.children.find((c) => rtid ? c.name === `RenderThread ${rtid}` : /^RenderThread \d+$/.test(c.name))),
      };
      Object.values(picks).forEach((n) => n && n.pin());
      try { app.sidebar.hide ? app.sidebar.hide() : app.sidebar.toggleVisibility && app.sidebar.toggleVisibility(); } catch (e) {}
      root.collapseAll && root.collapseAll();
      await new Promise((r) => setTimeout(r, 2500));
      return Object.fromEntries(Object.entries(picks).map(([k, n]) => [k, n ? n.name : null]));
    }, [visStart, visEnd, markers, renderTid]);
    console.log(JSON.stringify({ trace, picked }));
    await page.waitForTimeout(1500);
    // Clip to the pinned tracks: from the time ruler (fixed in the layout) to where the unpinned groups start.
    const top = 112;
    const bottom = (await page.getByText('CPU Scheduling', { exact: true }).first().boundingBox()).y - 2;
    await page.screenshot({ path: out, clip: { x: 0, y: top, width: cfg.width || 1600, height: bottom - top } });
    await page.close();
  }
  await browser.close();
})().catch((e) => { console.error(e); process.exit(1); });
