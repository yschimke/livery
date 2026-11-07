import { A2uiLitElement, basicCatalog } from '@a2ui/lit/v0_9';
import {
  ActionSchema,
  DynamicBooleanSchema,
  DynamicNumberSchema,
  DynamicStringSchema,
  DynamicValueSchema,
  type Catalog,
} from '@a2ui/web_core/v0_9';
import { css, html, nothing, type PropertyValues } from 'lit';
import { z } from 'zod';
import type { LiveryComponentSpec, LiveryPropertySpec, LiverySession, Rendered, Routed } from './livery.js';
import { RcCanvasPlayer, type NamedAction } from './player.js';

export interface LiveryCatalogOptions {
  /** The catalog id the client advertises: basic components plus one per Livery contract. */
  catalogId: string;
  /** The catalog to extend; A2UI's basic catalog by default. */
  base?: Catalog<any, any>;
  /** An action of a kind the page handles (`navigate`, `dismiss`, `app:…`) that the agent didn't wire. */
  onAppAction?: (kind: string, action: string, args: Record<string, string>, component: string) => void;
  /**
   * Something the agent should hear about: data that doesn't bind, no playable template, a `set` on
   * an input it didn't bind to a path. The page tells its agent (A2UI's `error` message).
   */
  onProblem?: (component: string, message: string) => void;
  /** What draws when there is no template (the agent is told through [onProblem]); nothing by default. */
  fallback?: (spec: LiveryComponentSpec, reason: string) => unknown;
}

/** One Livery component's A2UI API: its name, tag, element and props schema, and the session it draws with. */
export interface LiveryComponentApi {
  name: string;
  tagName: string;
  element: CustomElementConstructor;
  schema: z.ZodTypeAny;
  spec: LiveryComponentSpec;
  session: LiverySession;
  options: LiveryCatalogOptions;
}

/**
 * Material 3's baseline light scheme: the theme roles when the page sets neither `--livery-<role>`
 * nor A2UI's `--a2ui-color-<role>`. On Compose the host's MaterialTheme always supplies them; a
 * page that styles nothing must not draw a template with transparent colours.
 */
const BASELINE: Record<string, string> = {
  primary: '#FF6750A4', onPrimary: '#FFFFFFFF', secondary: '#FF625B71', onSecondary: '#FFFFFFFF',
  surface: '#FFF3EDF7', onSurface: '#FF1D1B20', surfaceVariant: '#FFE7E0EC', onSurfaceVariant: '#FF49454F',
  background: '#FFFEF7FF', onBackground: '#FF1D1B20', error: '#FFB3261E', onError: '#FFFFFFFF',
};

const THEME_ROLES = Object.keys(BASELINE);

/**
 * [options.base] (A2UI's basic catalog) plus one component per contract [session] can play now:
 * the catalog a client registers with its `MessageProcessor` and advertises as [catalogId].
 */
export async function liveryCatalog(session: LiverySession, options: LiveryCatalogOptions): Promise<{ catalog: Catalog<any, any>; components: LiveryComponentApi[] }> {
  const base = options.base ?? basicCatalog;
  const components = (await session.components()).map((spec) => liveryComponent(session, spec, options));
  const catalog = base.derive({ catalogId: options.catalogId, components: [...base.components.keys(), ...components] as any });
  return { catalog, components };
}

/**
 * The A2UI component for one contract ([spec]). Its element is defined once per tag, and takes its
 * session, spec and options from the catalog entry it was rendered from, never from the first
 * catalog that defined the tag: two catalogs (or a session opened again) each draw with their own.
 */
export function liveryComponent(session: LiverySession, spec: LiveryComponentSpec, options: LiveryCatalogOptions): LiveryComponentApi {
  const tagName = `livery-${kebab(spec.name)}`;
  let element = customElements.get(tagName);
  if (!element) {
    // One constructor per tag (the registry allows no other), all of them the same element.
    element = class extends LiveryElement {};
    customElements.define(tagName, element);
  }
  return { name: spec.name, tagName, element, schema: schemaOf(spec), spec, session, options };
}

/** The props schema, from the same projection as Kotlin's (`livery-a2ui`): A2UI's own dynamic types. */
function schemaOf(spec: LiveryComponentSpec): z.ZodTypeAny {
  const shape: Record<string, z.ZodTypeAny> = {};
  for (const p of spec.properties) {
    const s = propertySchema(p);
    shape[p.key] = p.required ? s : s.optional();
  }
  return z.object(shape).describe(spec.description);
}

function propertySchema(p: LiveryPropertySpec): z.ZodTypeAny {
  switch (p.kind) {
    case 'DynamicString': return DynamicStringSchema;
    case 'DynamicNumber': return DynamicNumberSchema;
    case 'DynamicBoolean': return DynamicBooleanSchema;
    case 'DynamicValue': return DynamicValueSchema;
    case 'String': return z.string();
    case 'Action': return ActionSchema;
    case 'Actions': return z.object(Object.fromEntries((p.properties ?? []).map((a) => [a, ActionSchema.optional()]))).strict();
    default: return z.any();
  }
}

const DYNAMIC = new Set(['DynamicString', 'DynamicNumber', 'DynamicBoolean', 'DynamicValue']);

/** Every Livery component's element: what it draws comes from its catalog entry ([LiveryComponentApi]). */
class LiveryElement extends A2uiLitElement {
    static styles = css`
      :host { display: block; width: 100%; height: var(--livery-a2ui-height, 200px); }
      .player { width: 100%; height: 100%; overflow: hidden; }
    `;

    private player?: RcCanvasPlayer;
    /** The player's canvas lives here, across renders: Lit never re-creates it. */
    private readonly box: HTMLDivElement = Object.assign(document.createElement('div'), { className: 'player' });
    private render_?: number;
    private request = '';
    private fallback?: string;
    private sequence = 0;
    private shownKey?: string;
    private resize?: ResizeObserver;
    private widthDp = 0;

    /** Documents this element's player loaded and values it set: a data change sets values, never reloads. */
    get liveryStats(): { loads: number; sets: number } | undefined {
      return this.player?.stats;
    }

    /** This element's catalog entry: the session, spec and options of the catalog it was rendered from. */
    private get livery(): LiveryComponentApi | undefined {
      const model = this.context?.componentModel;
      const entry = model?.catalog?.components?.get(model.type) as LiveryComponentApi | undefined;
      return entry?.session ? entry : undefined;
    }

    connectedCallback(): void {
      super.connectedCallback();
      // A new width can choose another design: render again when it changes by more than a little.
      this.resize = new ResizeObserver(() => {
        this.player?.resize(this.clientWidth, this.clientHeight, window.devicePixelRatio || 1);
        // A new width can choose another design: render again when it changes by more than a little.
        const w = Math.round(this.clientWidth);
        if (Math.abs(w - this.widthDp) > 8) { this.widthDp = w; this.request = ''; this.requestUpdate(); }
      });
      this.resize.observe(this);
    }

    disconnectedCallback(): void {
      super.disconnectedCallback();
      this.resize?.disconnect();
      if (this.render_ !== undefined) this.livery?.session.release(this.render_);
      this.render_ = undefined;
      this.player?.dispose();
      this.player = undefined;
      this.shownKey = undefined;
      this.request = '';
    }

    protected updated(changed: PropertyValues): void {
      super.updated(changed);
      void this.sync();
    }

    render() {
      const api = this.livery;
      if (this.fallback !== undefined) return html`${(api && api.options.fallback?.(api.spec, this.fallback)) ?? nothing}`;
      return html`${this.box}`;
    }

    /** The props Livery renders from: inputs and lists as A2UI resolved them, the rest as the agent wrote them. */
    private props(spec: LiveryComponentSpec): Record<string, unknown> {
      const resolved = (this.controller.props ?? {}) as Record<string, unknown>;
      const raw = this.context.componentModel.properties;
      const out: Record<string, unknown> = {};
      for (const p of spec.properties) {
        const value = DYNAMIC.has(p.kind) ? resolved[p.key] : raw[p.key];
        if (value !== undefined) out[p.key] = value;
      }
      return out;
    }

    private async sync(): Promise<void> {
      const api = this.livery;
      if (!api || !this.context) return;
      const { session, spec, options } = api;
      // One player per element, for as long as the element lives: its document stays and values move.
      this.player ??= new RcCanvasPlayer(this.box, (a) => void this.onAction(a));
      const props = this.props(spec);
      const density = window.devicePixelRatio || 1;
      const space = { widthDp: this.widthDp || Math.round(this.clientWidth) || undefined, heightDp: Math.round(this.clientHeight) || undefined, density, theme: themeOf(this) };
      const request = JSON.stringify([props, space]);
      if (request === this.request) return;
      this.request = request;
      const mine = ++this.sequence;
      const r: Rendered = await session.render(spec.name, props, space);
      if (mine !== this.sequence) return; // a newer render is on its way
      if (!r.ok && r.waiting) {
        // An image isn't on the device: fetch it off the render path, then render again. Not the agent's problem.
        if (await session.fetchImages()) { this.request = ''; this.requestUpdate(); }
        return;
      }
      if (!r.ok) {
        options.onProblem?.(spec.name, r.reason);
        this.fallback = r.reason;
        this.requestUpdate();
        return;
      }
      const wasFallback = this.fallback !== undefined;
      this.fallback = undefined;
      if (wasFallback) { this.requestUpdate(); await this.updateComplete; }
      const previous = this.render_;
      this.render_ = r.render;
      try {
        const first = previous === undefined;
        await this.player.show(r.documentKey, r.document, r.values, this.clientWidth, this.clientHeight, density);
        // An impression per document shown, as LiveryTemplate reports after its first frame.
        if (first || this.shownKey !== r.documentKey) session.shown(r.render);
        this.shownKey = r.documentKey;
      } catch (e) {
        options.onProblem?.(spec.name, `the player could not play ${r.design}: ${(e as Error).message}`);
        this.player.forget();
      }
      if (previous !== undefined) session.release(previous);
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
      const routed: Routed = await session.action(this.render_, a.name, a.valueType, value);
      switch (routed.route) {
        case 'agent':
          return this.dispatch(routed);
        case 'set': {
          const binding = this.context.componentModel.properties[routed.input] as { path?: unknown } | undefined;
          if (binding && typeof binding === 'object' && typeof binding.path === 'string') {
            this.context.dataContext.set(binding.path, routed.value);
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
      void this.context.dispatchAction(agent.action as any);
    }
}

/**
 * Theme role inputs from CSS: `--livery-<role>` (kebab-case), else A2UI's own `--a2ui-color-<role>`,
 * else Material 3's baseline, as `#AARRGGBB`.
 */
function themeOf(el: HTMLElement): Record<string, string> {
  const style = getComputedStyle(el);
  const out: Record<string, string> = {};
  for (const role of THEME_ROLES) {
    const name = kebab(role);
    const value = style.getPropertyValue(`--livery-${name}`).trim() || style.getPropertyValue(`--a2ui-color-${name}`).trim();
    out[role] = (value ? cssColorToArgb(value, el) : undefined) ?? BASELINE[role];
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

function kebab(s: string): string {
  return s.replace(/([a-z0-9])([A-Z])/g, '$1-$2').replace(/[^A-Za-z0-9]+/g, '-').toLowerCase();
}
