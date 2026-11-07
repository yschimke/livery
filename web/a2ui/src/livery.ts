/**
 * Livery's web session: Kotlin's own pipeline (`livery-web`, compiled to Wasm), behind a typed
 * API. Nothing here selects, trusts or binds: it passes JSON across and back.
 */
export interface LiveryConfig {
  /** The catalog's base URL; `manifest.json` is fetched from beside it. */
  catalog: string;
  /** Trusted Ed25519 keys, the public key as base64 of its raw 32 bytes. */
  keys?: { keyId: string; publicKey: string; contracts?: string[] }[];
  /** Accept an unsigned catalog: development only. */
  unsigned?: boolean;
  /** Documents an agent sends with a message (`template.inline`): refused unless set. */
  agentBytes?: { contracts: string[]; maxBytes?: number; allowedKinds?: string[] };
  /** Risky features a signed inline document may use (`shaders`, …). */
  signedFeatures?: string[];
  /** Hosts images are fetched from directly. */
  imageHosts?: string[];
  /** URL prefixes an `open-url` action may open. */
  urlAllowlist?: string[];
  /** Action kinds the page handles itself when the agent didn't wire them. */
  appKinds?: string[];
}

export interface LiveryPropertySpec {
  key: string;
  kind: 'DynamicString' | 'DynamicNumber' | 'DynamicBoolean' | 'DynamicValue' | 'String' | 'Action' | 'Actions' | 'Any';
  required: boolean;
  /** For `Actions`: the contract's action names. */
  properties?: string[];
}

export interface LiveryComponentSpec {
  name: string;
  contract: string;
  description: string;
  /** The component's JSON schema, as A2UI advertises it. */
  schema: Record<string, unknown>;
  properties: LiveryPropertySpec[];
  /** Inputs the contract's `set` actions write back. */
  setInputs: string[];
}

export interface LiverySpace {
  widthDp?: number;
  heightDp?: number;
  density?: number;
  /** Theme roles (`primary`, `surface`, `onSurface`, …) as `#RRGGBB` or `#AARRGGBB`. */
  theme?: Record<string, string>;
}

export type Rendered =
  | { ok: true; render: number; bytes: Uint8Array; design: string; sha256: string }
  | { ok: false; reason: string };

export type Routed =
  | { route: 'agent'; action: Record<string, unknown>; shadowed: string[] }
  | { route: 'set'; input: string; value: unknown; agent?: { action: Record<string, unknown>; shadowed: string[] } }
  | { route: 'app'; kind: string; action: string; args: Record<string, string> }
  | { route: 'dropped'; reason: string };

/** The `livery-web` module's exports (`livery-livery-web.mjs`). */
export interface LiveryWebModule {
  liveryOpen(config: string): Promise<unknown>;
  liveryRefresh(session: number): Promise<unknown>;
  liveryComponents(session: number): Promise<unknown>;
  liveryCatalogSchema(session: number, catalogId: string): Promise<unknown>;
  liveryRender(session: number, name: string, props: string, space: string): Promise<unknown>;
  liveryShown(session: number, render: number): void;
  liveryRelease(session: number, render: number): void;
  liveryAction(session: number, render: number, name: string, valueType: string, value: string | null): Promise<unknown>;
  liveryEvents(session: number): string;
}

/** Loads the Wasm module shipped in this package (`dist/livery-web/`). */
export async function loadLiveryWeb(): Promise<LiveryWebModule> {
  // The path is computed so a bundler leaves it alone: the module fetches its .wasm beside itself.
  const url = new URL('./livery-web/livery-livery-web.mjs', import.meta.url).href;
  return (await import(/* @vite-ignore */ url)) as LiveryWebModule;
}

export class LiverySession {
  private constructor(private readonly lib: LiveryWebModule, readonly id: number) {}

  /** Opens [config]'s catalog (fetching it once) with [lib], or the module this package ships. */
  static async open(config: LiveryConfig, lib?: LiveryWebModule): Promise<LiverySession> {
    const m = lib ?? (await loadLiveryWeb());
    return new LiverySession(m, Number(await m.liveryOpen(JSON.stringify(config))));
  }

  async refresh(): Promise<string> {
    return String(await this.lib.liveryRefresh(this.id));
  }

  /** The components to register: one per contract the catalog can play here now. */
  async components(): Promise<LiveryComponentSpec[]> {
    return JSON.parse(String(await this.lib.liveryComponents(this.id)));
  }

  /** The A2UI catalog JSON of those components, to prompt an agent with. */
  async catalogSchema(catalogId: string): Promise<Record<string, unknown>> {
    return JSON.parse(String(await this.lib.liveryCatalogSchema(this.id, catalogId)));
  }

  async render(name: string, props: Record<string, unknown>, space: LiverySpace = {}): Promise<Rendered> {
    const r = JSON.parse(String(await this.lib.liveryRender(this.id, name, JSON.stringify(props), JSON.stringify(space))));
    return r.ok ? { ...r, bytes: base64ToBytes(r.bytes) } : r;
  }

  shown(render: number): void {
    this.lib.liveryShown(this.id, render);
  }

  release(render: number): void {
    this.lib.liveryRelease(this.id, render);
  }

  async action(render: number, name: string, valueType: string, value: unknown): Promise<Routed> {
    const text = value === null || value === undefined ? null : String(value);
    return JSON.parse(String(await this.lib.liveryAction(this.id, render, name, valueType, text)));
  }

  /** Livery's events since the last call: impressions, actions, fallbacks, errors. */
  events(): Record<string, unknown>[] {
    return JSON.parse(this.lib.liveryEvents(this.id));
  }
}

function base64ToBytes(b64: string): Uint8Array {
  const s = atob(b64);
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}
