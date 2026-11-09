import { descriptor, previewUrl, type PreviewDescriptor } from './descriptor.js';
import { RcCanvasPlayer, type NamedAction } from './player.js';
import { WebSession, type WebConfig, type Ready, type Routed } from './session.js';

export interface PreviewOptions {
  /** Host-owned trust and capabilities. These cannot be supplied by the descriptor. */
  config: Omit<WebConfig, 'catalog'>;
  /** Minimal embeds retain theme/reset; full previews also expose width/examples. */
  controls?: 'full' | 'minimal';
  /** Developer diagnostics, hidden by public publication pages. */
  showActionLog?: boolean;
  fetch?: typeof fetch;
  /** Host-provided example overrides; never application scripts. */
  examples?: Record<string, Record<string, unknown>>;
}
const light = { primary: '#FF6750A4', onSurface: '#FF1D1B20', surface: '#FFF7F2FA' };
const dark = { primary: '#FFD0BCFF', onSurface: '#FFE6E1E5', surface: '#FF211F26' };

/** Experimental fixture viewer. Each instance owns its data and its Wasm session. */
export class ComponentPreview {
  private session?: WebSession;
  private player?: RcCanvasPlayer;
  private publication?: PreviewDescriptor;
  private render?: Ready;
  private data: Record<string, unknown> = {};
  private sequence = 0;
  private disposed = false;
  private inView = true;
  private theme = light;
  private width = 360;
  private readonly abort = new AbortController();
  private readonly observer: IntersectionObserver;
  private readonly resizeObserver: ResizeObserver;
  private renderedWidth = 0;
  private readonly surface = document.createElement('div');
  private readonly status = document.createElement('p');
  private readonly log = document.createElement('pre');
  private readonly controls = document.createElement('div');
  private readonly visibility = () => {
    if (document.hidden || !this.inView) this.suspend();
    else void this.update();
  };
  readonly ready: Promise<void>;
  readonly actions: Routed[] = [];

  constructor(readonly element: HTMLElement, url: string, private options: PreviewOptions) {
    const link = document.createElement('a');
    link.href = new URL(url, location.href).href;
    link.textContent = 'Open preview';
    const title = document.createElement('h2'); title.textContent = 'Component preview';
    this.status.setAttribute('role', 'status');
    this.log.setAttribute('role', 'log'); this.log.setAttribute('aria-label', 'Preview actions');
    this.surface.setAttribute('aria-label', 'Interactive Remote Compose component');
    this.controls.style.cssText = 'display:flex;gap:12px;flex-wrap:wrap;margin:12px 0';
    this.surface.style.maxWidth = '100%';
    this.element.replaceChildren(title, link, this.controls, this.surface, this.status);
    if (this.options.showActionLog !== false) this.element.append(this.log);
    this.addControls();
    document.addEventListener('visibilitychange', this.visibility);
    this.observer = new IntersectionObserver(entries => { this.inView = entries[0]?.isIntersecting ?? false; this.visibility(); });
    this.observer.observe(element);
    this.resizeObserver = new ResizeObserver(() => { if (this.surface.clientWidth !== this.renderedWidth) void this.update(); });
    this.resizeObserver.observe(this.surface);
    this.ready = this.open(new URL(url, location.href).href, title, link);
  }

  get values(): Record<string, unknown> { return { ...this.data }; }
  get stats(): { loads: number; sets: number } { return this.player ? { ...this.player.stats } : { loads: 0, sets: 0 }; }
  get state(): string { return this.element.dataset.state ?? 'loading'; }

  private async open(url: string, title: HTMLElement, link: HTMLAnchorElement): Promise<void> {
    this.setStatus('loading', 'Loading preview…');
    try {
      previewUrl(url);
      const response = await (this.options.fetch ?? fetch)(url, { signal: this.abort.signal, credentials: 'omit', redirect: 'error' });
      if (!response.ok) throw new Error(`Preview unavailable (${response.status})`);
      const bytes = await response.arrayBuffer();
      if (bytes.byteLength > 64 * 1024) throw new Error('Preview descriptor is too large');
      const text = new TextDecoder().decode(bytes);
      const pub = descriptor(JSON.parse(text), url);
      if (this.disposed) return;
      this.publication = pub; this.data = structuredClone(pub.data);
      title.textContent = pub.fallback.title; link.href = pub.canonicalUrl;
      const opened = await WebSession.open({ ...this.options.config, catalog: new URL('./', pub.catalog.manifestUrl).href }, this.abort.signal);
      if (this.disposed) { opened.close(); return; }
      this.session = opened;
      await this.update();
    } catch (error) { if (!this.disposed) this.fail(error); }
  }

  /** Takes example data; the core binder, not this UI, decides whether it fits. */
  async setData(data: Record<string, unknown>): Promise<void> { this.data = structuredClone(data); await this.update(); }
  async reset(): Promise<void> { if (this.publication) { this.actions.length = 0; this.log.textContent = ''; const example = this.controls.querySelector<HTMLSelectElement>('[aria-label="Example"]'); if (example) example.value = 'Default'; await this.setData(this.publication.data); } }

  async dispatch(action: NamedAction): Promise<void> {
    const active = this.render, session = this.session;
    if (this.disposed || !active || !session) return;
    const result = await session.action(active.render, action.name, action.valueType, action.value);
    if (this.disposed || this.render !== active) return;
    this.actions.push(result);
    this.log.textContent = this.actions.slice(-8).map(a => JSON.stringify(a)).join('\n');
    if (result.route === 'set') {
      this.data = { ...this.data, [result.input]: result.value };
      await this.update();
    }
  }

  private async update(): Promise<void> {
    const session = this.session, pub = this.publication;
    if (!session || !pub || this.disposed || document.hidden || !this.inView) return;
    this.surface.style.width = `${this.width}px`;
    const effectiveWidth = Math.min(this.width, this.surface.clientWidth || this.width);
    this.renderedWidth = effectiveWidth;
    const mine = ++this.sequence;
    const previous = this.render;
    try {
      const space = { widthDp: effectiveWidth, density: devicePixelRatio || 1, theme: this.theme, colorScheme: this.theme === dark ? 'dark' : 'light', decisionId: `preview:${pub.publicationId}` };
      let r = await session.render(pub.template.contract, this.data, space, pub.template.designId, pub.template.sha256);
      if (!r.ok && r.waiting && mine === this.sequence) {
        this.setStatus('loading', 'Loading component resources…');
        await session.fetchImages(); // explicitly outside the cache-only render
        if (mine !== this.sequence || this.disposed) return;
        r = await session.render(pub.template.contract, this.data, space, pub.template.designId, pub.template.sha256);
      }
      if (mine !== this.sequence || this.disposed) { if (r.ok) session.release(r.render); return; }
      if (!r.ok) { this.suspend(); this.setStatus('fallback', `${pub.fallback.description} (${r.reason})`); return; }
      if (r.sha256 !== pub.template.sha256 || r.design !== pub.template.designId) { session.release(r.render); throw new Error('Pinned component unavailable'); }
      this.render = r;
      this.surface.style.width = `${this.width}px`;
      this.surface.style.height = `${r.heightDp ?? 120}px`;
      this.player ??= new RcCanvasPlayer(this.surface, a => { void this.dispatch(a).catch(e => this.fail(e)); }, { reloadOnResize: true });
      const shown = await this.player.show(r.documentKey, r.document, r.values, effectiveWidth, r.heightDp ?? 120, devicePixelRatio || 1);
      if (mine === this.sequence && !this.disposed && shown) {
        if (!previous || previous.documentKey !== r.documentKey) session.shown(r.render);
        this.setStatus('ready', 'Interactive preview — changes stay in this preview.');
      }
      if (previous) session.release(previous.render);
    } catch (error) { if (previous) session.release(previous.render); if (mine === this.sequence && !this.disposed) { this.suspend(); this.fail(error); } }
  }

  private suspend(): void {
    this.sequence++;
    if (this.render) this.session?.release(this.render.render);
    this.render = undefined;
    this.player?.dispose(); this.player = undefined;
    this.surface.replaceChildren();
  }
  dispose(): void {
    if (this.disposed) return;
    this.disposed = true; this.abort.abort(); this.observer.disconnect(); this.resizeObserver.disconnect();
    document.removeEventListener('visibilitychange', this.visibility);
    this.suspend(); this.session?.close(); this.session = undefined;
    this.controls.replaceChildren(); this.setStatus('disposed', 'Preview closed');
  }
  private fail(error: unknown): void { this.setStatus('fallback', `${this.publication?.fallback.description ?? 'Preview unavailable'} — ${error instanceof Error ? error.message : String(error)}`); }
  private setStatus(state: string, text: string): void { this.element.dataset.state = state; this.status.textContent = text; }
  private addControls(): void {
    const select = (label: string, values: string[], change: (value: string) => void) => {
      const wrapper = document.createElement('label'); wrapper.textContent = `${label} `;
      const field = document.createElement('select'); field.setAttribute('aria-label', label);
      for (const value of values) field.add(new Option(value, value));
      field.addEventListener('change', () => change(field.value)); wrapper.append(field); this.controls.append(wrapper);
    };
    select('Theme', ['Light', 'Dark'], value => { this.theme = value === 'Dark' ? dark : light; void this.update(); });
    if (this.options.controls !== 'minimal') select('Width', ['360', '280', '480'], value => { this.width = Number(value); void this.update(); });
    if (this.options.controls !== 'minimal' && Object.keys(this.options.examples ?? {}).length) select('Example', ['Default', ...Object.keys(this.options.examples ?? {})], value => { if (this.publication) void this.setData({ ...this.publication.data, ...this.options.examples?.[value] }); });
    const reset = document.createElement('button'); reset.textContent = 'Reset'; reset.addEventListener('click', () => { void this.reset(); }); this.controls.append(reset);
  }
}
