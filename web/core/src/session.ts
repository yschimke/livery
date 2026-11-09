export interface WebConfig {
  catalog: string;
  keys?: { keyId: string; publicKey: string; contracts?: string[] }[];
  /** Development only. A descriptor cannot opt its own host into this policy. */
  unsigned?: boolean;
  capabilities?: { surface: string; maxRcApiLevel: number; profiles: string[]; features?: string[]; maxBytes?: number };
  imageHosts?: string[];
  urlAllowlist?: string[];
  appKinds?: string[];
}
export interface Space { colorScheme?: string; widthDp?: number; heightDp?: number; density?: number; theme?: Record<string, string>; decisionId?: string }
export type Ready = { ok: true; render: number; document: Uint8Array; documentKey: string; values: Record<string, string | number>; design: string; sha256: string; heightDp?: number };
export type Rendered = Ready | { ok: false; waiting?: boolean; reason: string };
export type Routed = { route: 'set'; input: string; value: unknown } | { route: 'app'; kind: string; action: string; args: Record<string, string> } | { route: 'dropped'; reason: string };
interface Module {
  previewCreate(config: string): number;
  previewConnect(id: number): Promise<unknown>;
  previewRender(id: number, contract: string, data: string, space: string, design: string | null, sha: string | null): Promise<unknown>;
  previewAction(id: number, render: number, name: string, type: string, value: string | null): Promise<unknown>;
  previewFetchImages(id: number): Promise<unknown>;
  previewShown(id: number, render: number): void;
  previewRelease(id: number, render: number): void;
  previewClose(id: number): void;
}
let loading: Promise<Module> | undefined;
function module(): Promise<Module> {
  const url = new URL('./livery-web/livery-livery-web-core.mjs', import.meta.url).href;
  return loading ??= import(/* @vite-ignore */ url);
}
/** Typed transport only: selection, binding and action validation happen in Kotlin. */
export class WebSession {
  private closed = false;
  private constructor(private lib: Module, readonly id: number) {}
  static async open(config: WebConfig, signal?: AbortSignal): Promise<WebSession> {
    const lib = await module();
    signal?.throwIfAborted();
    const id = lib.previewCreate(JSON.stringify(config));
    const close = () => lib.previewClose(id);
    signal?.addEventListener('abort', close, { once: true });
    try {
      await lib.previewConnect(id);
      signal?.throwIfAborted();
      return new WebSession(lib, id);
    } catch (error) { close(); throw error; }
    finally { signal?.removeEventListener('abort', close); }
  }
  async render(contract: string, data: Record<string, unknown>, space: Space, design?: string, sha?: string): Promise<Rendered> {
    this.check();
    const r = JSON.parse(String(await this.lib.previewRender(this.id, contract, JSON.stringify(data), JSON.stringify(space), design ?? null, sha ?? null)));
    if (r.ok) r.document = Uint8Array.from(atob(r.document), c => c.charCodeAt(0));
    return r;
  }
  async action(render: number, name: string, type: string, value: unknown): Promise<Routed> {
    if (this.closed) return { route: 'dropped', reason: 'preview disposed' };
    return JSON.parse(String(await this.lib.previewAction(this.id, render, name, type, value == null ? null : String(value))));
  }
  async fetchImages(): Promise<boolean> { this.check(); return String(await this.lib.previewFetchImages(this.id)) === 'true'; }
  shown(render: number): void { if (!this.closed) this.lib.previewShown(this.id, render); }
  release(render: number): void { if (!this.closed) this.lib.previewRelease(this.id, render); }
  close(): void { if (!this.closed) { this.closed = true; this.lib.previewClose(this.id); } }
  private check(): void { if (this.closed) throw new Error('preview disposed'); }
}
