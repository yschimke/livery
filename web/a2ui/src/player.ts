import { createHostApi, createPlayer } from '../vendor/remote-compose-player.mjs';
import type { HostApi, HostValue, RcPlayerHandle } from '../vendor/remote-compose-player.mjs';

/** A host named action the document sent: its name and value, as the player reported them. */
export interface NamedAction {
  name: string;
  valueType: 'none' | 'string' | 'int' | 'float';
  value: unknown;
}

/** A persistent in-page renderer. Superseded shows resolve false; dispose cancels pending work. */
export interface LiveryPlayer {
  /** Finite wrap-content height when supported by this player. */
  readonly measuredHeightDp?: number;
  show(key: string, document: Uint8Array, values: Record<string, HostValue>, widthPx: number, heightPx: number, density: number): Promise<boolean>;
  resize(widthPx: number, heightPx: number, density: number): void;
  forget(): void;
  dispose(): void;
  readonly stats: { loads: number; sets: number };
}

export type LiveryPlayerFactory = (container: HTMLElement, onAction: (action: NamedAction) => void) => LiveryPlayer;

/**
 * The TypeScript Remote Compose player on a canvas in [container], in the page itself. A document
 * is loaded once; its named values are set on it while it plays, so a data change moves the values
 * and never reloads the document (Remote Compose's model, as `LiveryTemplate` does on Compose).
 */
export class RcCanvasPlayer implements LiveryPlayer {
  private readonly handle: RcPlayerHandle;
  private readonly host: HostApi;
  private key?: string;
  private values = new Map<string, string>();
  /** Shows run one after another; one asked for while another runs replaces any still queued. */
  private queue: Promise<unknown> = Promise.resolve();
  private latest = 0;
  /** Last root layout height, in dp, after applying named values. */
  measuredHeightDp?: number;
  private disposed = false;
  private epoch = 0;
  /** Documents loaded and values set, for tests and debugging. */
  readonly stats = { loads: 0, sets: 0 };

  constructor(container: HTMLElement, onAction: (a: NamedAction) => void) {
    this.handle = createPlayer(container, {});
    this.handle.canvas.style.display = 'block';
    this.host = createHostApi(this.handle.player);
    this.host.onAction('*', (value, name) => onAction({ name: name.replace(/^USER:/, ''), valueType: valueTypeOf(value), value }));
  }

  /**
   * Shows [documentKey]'s document with [values]: loads [document] only when the key differs from
   * the one playing, then sets each value that changed. A load is asynchronous, so shows are
   * queued: each waits for the one before it, and a show superseded while it waits does nothing
   * (resolves `false`), so an older one can never land after a newer one.
   */
  show(documentKey: string, document: Uint8Array, values: Record<string, HostValue>, widthPx: number, heightPx: number, density: number): Promise<boolean> {
    const mine = ++this.latest;
    const run = this.queue.then(async () => {
      if (this.disposed || mine !== this.latest) return false;
      await this.apply(documentKey, document, values, widthPx, heightPx, density);
      return !this.disposed && mine === this.latest;
    });
    this.queue = run.catch(() => {});
    return run;
  }

  private async apply(documentKey: string, document: Uint8Array, values: Record<string, HostValue>, widthPx: number, heightPx: number, density: number): Promise<void> {
    if (documentKey !== this.key) {
      const epoch = this.epoch;
      this.handle.player.setDensity(density);
      // Sized before the load too: the player fixes its touch mapping from the canvas a document
      // loads into, so a document loaded at the default 256 px missed taps past it.
      this.resize(widthPx, heightPx, density);
      await this.handle.loadFromArrayBuffer(document.slice().buffer);
      if (this.disposed) { this.handle.destroy(); return; }
      if (epoch !== this.epoch) return;
      this.key = documentKey;
      this.values.clear();
      this.stats.loads++;
      this.resize(widthPx, heightPx, density);
    }
    // A document's variables are in Remote Compose's `USER:` domain; set each by the name it declares.
    const declared = this.host.variables();
    for (const [name, value] of Object.entries(values)) {
      const text = JSON.stringify(value);
      if (this.values.get(name) === text) continue;
      const as = `USER:${name}` in declared ? `USER:${name}` : name;
      if (!(as in declared)) continue; // the document doesn't read it
      this.host.set(as, value);
      this.values.set(name, text);
      this.stats.sets++;
    }
    this.handle.player.repaint();
    const measured = this.handle.player.measureSize();
    this.measuredHeightDp = measured ? measured.height / density : undefined;
  }

  /** The canvas at [widthPx] × [heightPx] CSS pixels. */
  resize(widthPx: number, heightPx: number, density: number): void {
    if (widthPx > 0 && heightPx > 0) this.handle.resize(Math.round(widthPx * density), Math.round(heightPx * density));
  }

  /** Forgets the document playing, so the next [show] loads its document again. */
  forget(): void {
    this.latest++;
    this.epoch++;
    this.key = undefined;
    this.values.clear();
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.latest++;
    this.host.dispose();
    this.handle.destroy();
  }
}

function valueTypeOf(value: unknown): NamedAction['valueType'] {
  if (value === undefined || value === null) return 'none';
  if (typeof value === 'number') return Number.isInteger(value) ? 'int' : 'float';
  return 'string';
}
