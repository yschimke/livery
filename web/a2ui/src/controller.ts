import type { ComponentContext } from '@a2ui/web_core/v0_9';
import type { LiveryComponentApi } from './api.js';
import { kebab } from './api.js';
import type { LiveryComponentSpec, Rendered, Routed } from './livery.js';
import { RcCanvasPlayer, type NamedAction, type LiveryPlayer } from './player.js';

export const hostStyle = 'display:block;width:100%;height:var(--livery-a2ui-height,var(--livery-template-height,200px))';
const BASELINE: Record<string, string> = {
  primary: '#FF6750A4', onPrimary: '#FFFFFFFF', secondary: '#FF625B71', onSecondary: '#FFFFFFFF',
  surface: '#FFF3EDF7', onSurface: '#FF1D1B20', surfaceVariant: '#FFE7E0EC', onSurfaceVariant: '#FF49454F',
  background: '#FFFEF7FF', onBackground: '#FF1D1B20', error: '#FFB3261E', onError: '#FFFFFFFF',
};

const THEME_ROLES = Object.keys(BASELINE);

const DYNAMIC = new Set(['DynamicString', 'DynamicNumber', 'DynamicBoolean', 'DynamicValue']);
const DESIGN = 'design';
const TEMPLATE = 'template';

export class LiveryController {
    private livery?: LiveryComponentApi;
    private context?: ComponentContext;
    private resolved: Record<string, unknown> = {};
    private connected = false;
    constructor(readonly element: HTMLElement, private readonly changed: () => void, private readonly settled: () => Promise<unknown>) {
      Object.defineProperties(element, {
        liveryStats: { configurable: true, get: () => this.liveryStats },
        liveryTheme: { configurable: true, get: () => this.liveryTheme },
        liveryHostAction: { configurable: true, value: (a: NamedAction) => this.liveryHostAction(a) },
      });
      this.box.style.cssText = 'width:100%;height:100%;overflow:hidden';
    }
    connect(): void {
      this.connected = true;
      this.resize = new ResizeObserver(() => {
        this.player?.resize(this.element.clientWidth, this.element.clientHeight, window.devicePixelRatio || 1);
        const w = Math.round(this.element.clientWidth);
        if (Math.abs(w - this.widthDp) > 8) { this.widthDp = w; this.request = ''; this.invalidate(); }
      });
      this.resize.observe(this.element);
    }
    update(api: LiveryComponentApi | undefined, context: ComponentContext, resolved: Record<string, unknown>): void {
      if (this.livery && this.livery !== api) { this.disconnect(); this.connect(); }
      this.livery = api; this.context = context; this.resolved = resolved;
      if (this.connected) void this.sync();
    }
    private invalidate(): void {
      if (!this.connected) return;
      this.changed();
      queueMicrotask(() => { if (this.connected) void this.sync(); });
    }
    disconnect(): void {
      this.connected = false;
      this.resize?.disconnect();
      this.sequence++;
      if (this.render_ !== undefined) this.livery?.session.release(this.render_);
      this.render_ = undefined;
      this.player?.dispose(); this.player = undefined;
      this.shownKey = undefined; this.request = '';
      this.automatic = false; this.automaticFor = undefined; this.imagesFailed = false;
    }
    private player?: LiveryPlayer;
    /** The player's canvas lives here across framework renders. */
    readonly box: HTMLDivElement = Object.assign(document.createElement('div'), { className: 'player' });
    private render_?: number;
    private request = '';
    fallback?: string;
    private sequence = 0;
    /** The agent's design failed to play: render the automatic choice instead, until the props change. */
    private automatic = false;
    private lastTheme?: Record<string, string>;
    private automaticFor?: string;
    /** A fetch for this element's images failed: if it renders waiting again, it can't draw. */
    private imagesFailed = false;
    private shownKey?: string;
    private resize?: ResizeObserver;
    private widthDp = 0;

    /** Documents this element's player loaded and values it set: a data change sets values, never reloads. */
    get liveryStats(): { loads: number; sets: number } | undefined {
      return this.player?.stats;
    }

    /** The theme roles of the last render, as `#AARRGGBB`. @internal For tests. */
    get liveryTheme(): Record<string, string> | undefined {
      return this.lastTheme;
    }

    /** The props Livery renders from: inputs and lists as A2UI resolved them, the rest as the agent wrote them. */
    private props(spec: LiveryComponentSpec): Record<string, unknown> {
      const resolved = (this.resolved ?? {}) as Record<string, unknown>;
      const raw = this.context!.componentModel.properties;
      const out: Record<string, unknown> = {};
      for (const p of spec.properties) {
        const value = DYNAMIC.has(p.kind) ? resolved[p.key] : raw[p.key];
        if (value !== undefined) out[p.key] = value;
      }
      return out;
    }

    /** Whether the page sets the component's height (`--livery-a2ui-height`), so selection can use it. */
    private pageHeight(): boolean {
      return getComputedStyle(this.element).getPropertyValue('--livery-a2ui-height').trim() !== '';
    }

    /** `a2ui:<surfaceId>/<componentId>`: on every event of this component, so analytics can tell agent-placed templates from the app's own. */
    private decisionId(): string | undefined {
      const component = this.context?.componentModel.id;
      const surface = this.context?.dataContext.surface?.id;
      return component && surface ? `a2ui:${surface}/${component}` : undefined;
    }

    async sync(): Promise<void> {
      const api = this.livery;
      if (!api || !this.context) return;
      const { session, spec, options } = api;
      // One player per element, for as long as the element lives: its document stays and values move.
      this.player ??= typeof options.player === 'function'
        ? options.player(this.box, (a) => void this.onAction(a))
        : new RcCanvasPlayer(this.box, (a) => void this.onAction(a));
      const props = this.props(spec);
      // After the chosen design failed to play, this element renders the automatic choice, until
      // the agent changes the component.
      const asked = JSON.stringify(props);
      if (this.automaticFor !== undefined && this.automaticFor !== asked) this.automatic = false;
      if (this.automatic) { this.automaticFor ??= asked; delete props[DESIGN]; } else this.automaticFor = undefined;
      const density = window.devicePixelRatio || 1;
      const space = { widthDp: this.widthDp || Math.round(this.element.clientWidth) || undefined, heightDp: this.pageHeight() ? Math.round(this.element.clientHeight) || undefined : undefined, density, theme: themeOf(this.element, this.context?.theme), decisionId: this.decisionId() };
      this.lastTheme = space.theme;
      const request = JSON.stringify([props, space]);
      if (request === this.request) return;
      this.request = request;
      const mine = ++this.sequence;
      let r: Rendered;
      try { r = await session.render(spec.name, props, space); }
      catch (error) { if (mine === this.sequence) this.cannotPlay(props, String(error)); return; }
      if (mine !== this.sequence) {
        // A newer render is on its way: this one is never shown, so the session can drop its plan.
        if (r.ok) session.release(r.render);
        return;
      }
      if (!r.ok && r.waiting && !this.imagesFailed) {
        // An image isn't on the device: fetch it off the render path, then render again. Not the agent's problem.
        session.whenImagesArrive((arrived) => {
          if (mine !== this.sequence) return;
          this.imagesFailed = !arrived;
          this.request = '';
          this.invalidate();
        });
        void session.fetchImages();
        return;
      }
      this.imagesFailed = false;
      if (!r.ok) {
        this.cannotPlay(props, r.waiting ? `an image could not be fetched: ${r.reason}` : r.reason);
        return;
      }
      const wasFallback = this.fallback !== undefined;
      this.fallback = undefined;
      if (wasFallback) { this.invalidate(); await this.settled(); }
      // The height rule: the page's --livery-a2ui-height when it sets one, else the template's own.
      // A template that doesn't say takes the default, never the last template's height.
      if (r.heightDp) this.element.style.setProperty('--livery-template-height', `${r.heightDp}px`);
      else this.element.style.removeProperty('--livery-template-height');
      if (mine !== this.sequence) { session.release(r.render); return; }
      const previous = this.render_;
      this.render_ = r.render;
      const player = this.player;
      try {
        const first = previous === undefined;
        const shown = await player.show(r.documentKey, r.document, r.values, this.element.clientWidth, this.element.clientHeight, density);
        if (shown && mine === this.sequence && !this.pageHeight() && player.measuredHeightDp) {
          const height = Math.max(r.minHeightDp ?? 0, player.measuredHeightDp);
          this.element.style.setProperty('--livery-template-height', `${height}px`);
          player.resize(this.element.clientWidth, height, density);
        }
        // An impression per document shown, as LiveryTemplate reports after its first frame. A
        // show a newer one replaced, or one the element left the page during, isn't one.
        if (shown && mine === this.sequence && (first || this.shownKey !== r.documentKey)) session.shown(r.render);
        if (shown && mine === this.sequence) this.shownKey = r.documentKey;
      } catch (e) {
        player.forget();
        if (mine === this.sequence) this.cannotPlay(props, `the player could not play ${r.design}: ${(e as Error).message}`);
      }
      if (previous !== undefined) session.release(previous);
    }

    /**
     * This render can't draw. The agent hears why. A chosen design falls back to the automatic
     * choice, never to nothing; the agent's own document is never replaced (PROTOCOL.md §5.3), so
     * it, and an automatic choice that can't draw either, show the page's fallback.
     */
    private cannotPlay(props: Record<string, unknown>, reason: string): void {
      this.livery?.options.onProblem?.(this.livery.spec.name, reason);
      if (props[DESIGN] !== undefined && props[TEMPLATE] === undefined && !this.automatic) {
        this.automatic = true;
        this.request = '';
      } else {
        this.fallback = reason;
      }
      this.invalidate();
    }

    /**
     * A host named action, exactly as the player's callback delivers one. @internal For tests that
     * send a document's action with values its buttons wouldn't (an undeclared argument).
     */
    liveryHostAction(a: NamedAction): Promise<void> {
      return this.onAction(a);
    }

    private async onAction(a: NamedAction): Promise<void> {
      const api = this.livery;
      if (this.render_ === undefined || !api) return;
      const { session, spec, options } = api;
      const value = a.valueType === 'none' ? null : a.value;
      const render = this.render_;
      const routed: Routed = await session.action(render, a.name, a.valueType, value);
      if (!this.connected || this.livery !== api || this.render_ !== render) return;
      switch (routed.route) {
        case 'agent':
          return this.dispatch(routed);
        case 'set': {
          const binding = this.context!.componentModel.properties[routed.input] as { path?: unknown } | undefined;
          if (binding && typeof binding === 'object' && typeof binding.path === 'string') {
            this.context!.dataContext.set(binding.path, routed.value);
          } else {
            // A literal (or missing) binding has nowhere to write: tell the agent, as Kotlin does.
            options.onProblem?.(spec.name, `${spec.name}.${routed.input} is not bound to a data-model path, so ${a.name} can't write it`);
          }
          if (routed.agent) this.dispatch(routed.agent);
          return;
        }
        case 'app':
          return options.onAppAction?.(routed.kind, routed.action, routed.args, spec.name);
        case 'dropped':
          return; // Livery reported it as an event.
      }
    }

    private dispatch(agent: { action: Record<string, unknown>; shadowed: string[] }) {
      const api = this.livery;
      if (api && agent.shadowed.length > 0) api.options.onProblem?.(api.spec.name, `the event context names the template's own arguments ${JSON.stringify(agent.shadowed)}`);
      void this.context!.dispatchAction(agent.action as any);
    }
}

/**
 * Theme role inputs from CSS: `--livery-<role>` (kebab-case), else A2UI's own `--a2ui-color-<role>`,
 * else Material 3's baseline, as `#AARRGGBB`.
 */
function themeOf(el: HTMLElement, surface?: Record<string, unknown>): Record<string, string> {
  const style = getComputedStyle(el);
  const out: Record<string, string> = {};
  // The agent's own colour first: A2UI's createSurface theme (`primaryColor` → `primary`).
  const agent: Record<string, unknown> = { primary: surface?.primaryColor };
  for (const role of THEME_ROLES) {
    const name = kebab(role);
    const asked = agent[role];
    const fromAgent = typeof asked === 'string' ? cssColorToArgb(asked, el) : undefined;
    const value = style.getPropertyValue(`--livery-${name}`).trim() || style.getPropertyValue(`--a2ui-color-${name}`).trim();
    out[role] = fromAgent ?? (value ? cssColorToArgb(value, el) : undefined) ?? BASELINE[role];
  }
  return out;
}

/**
 * Any CSS colour as `#AARRGGBB`, resolved where [el] is (so `light-dark()` and `var()` follow its
 * colour scheme), or undefined when it isn't a colour.
 */
function cssColorToArgb(color: string, el: HTMLElement): string | undefined {
  if (!CSS.supports('color', color)) return undefined;
  const probe = document.createElement('span');
  probe.style.display = 'none';
  probe.style.color = color;
  (el.shadowRoot ?? el).appendChild(probe);
  const resolved = getComputedStyle(probe).color;
  probe.remove();
  const m = resolved.match(/^rgba?\(([^)]+)\)$/);
  if (!m) return undefined;
  const parts = m[1].split(/[\s,/]+/).filter(Boolean).map(Number);
  const [r, g, b] = parts;
  const a = parts.length > 3 ? parts[3] : 1;
  const hex = (n: number) => Math.round(Math.min(255, Math.max(0, n))).toString(16).padStart(2, '0').toUpperCase();
  return `#${hex(a * 255)}${hex(r)}${hex(g)}${hex(b)}`;
}
