import { createHostApi, createPlayer } from '../vendor/remote-compose-player.mjs';
import type { HostApi, HostValue, RcPlayerHandle } from '../vendor/remote-compose-player.mjs';

/** A host named action the document sent: its name and value, as the player reported them. */
export interface NamedAction {
  name: string;
  valueType: 'none' | 'string' | 'int' | 'float';
  value: unknown;
}

/**
 * The TypeScript Remote Compose player on a canvas in [container], in the page itself. A document
 * is loaded once; its named values are set on it while it plays, so a data change moves the values
 * and never reloads the document (Remote Compose's model, as `LiveryTemplate` does on Compose).
 */
export class RcCanvasPlayer {
  private readonly handle: RcPlayerHandle;
  private readonly host: HostApi;
  private key?: string;
  private values = new Map<string, string>();
  /** Documents loaded and values set, for tests and debugging. */
  readonly stats = { loads: 0, sets: 0 };

  constructor(container: HTMLElement, onAction: (a: NamedAction) => void) {
    this.handle = createPlayer(container, {});
    this.handle.canvas.style.display = 'block';
    this.host = createHostApi(this.handle.player);
    this.host.onAction('*', (value, name) => onAction({ name, valueType: valueTypeOf(value), value }));
  }

  /**
   * Shows [documentKey]'s document with [values]: loads [document] only when the key differs from
   * the one playing, then sets each value that changed.
   */
  async show(documentKey: string, document: Uint8Array, values: Record<string, HostValue>, widthPx: number, heightPx: number, density: number): Promise<void> {
    if (documentKey !== this.key) {
      this.handle.player.setDensity(density);
      await this.handle.loadFromArrayBuffer(document.slice().buffer);
      this.key = documentKey;
      this.values.clear();
      this.stats.loads++;
      this.resize(widthPx, heightPx, density);
    }
    for (const [name, value] of Object.entries(values)) {
      const text = JSON.stringify(value);
      if (this.values.get(name) === text) continue;
      this.host.set(name, value);
      this.values.set(name, text);
      this.stats.sets++;
    }
  }

  /** The canvas at [widthPx] × [heightPx] CSS pixels. */
  resize(widthPx: number, heightPx: number, density: number): void {
    if (widthPx > 0 && heightPx > 0) this.handle.resize(Math.round(widthPx * density), Math.round(heightPx * density));
  }

  /** Forgets the document playing, so the next [show] loads its document again. */
  forget(): void {
    this.key = undefined;
    this.values.clear();
  }

  dispose(): void {
    this.host.dispose();
    this.handle.destroy();
  }
}

function valueTypeOf(value: unknown): NamedAction['valueType'] {
  if (value === undefined || value === null) return 'none';
  if (typeof value === 'number') return Number.isInteger(value) ? 'int' : 'float';
  return 'string';
}
