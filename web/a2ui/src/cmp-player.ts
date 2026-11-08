import type { HostValue } from '../vendor/remote-compose-player.mjs';
import type { LiveryPlayer, LiveryPlayerFactory, NamedAction } from './player.js';
import { RcCanvasPlayer } from './player.js';

interface CmpModule {
  liveryCmpPrepare(font: string, symbols: string): void;
  liveryCmpMount(element: HTMLElement, onAction: (name: string, type: string, value: string | null) => void): number;
  liveryCmpShow(id: number, key: string, bytes: string, values: string, density: number): Promise<boolean>;
  liveryCmpResize(id: number, density: number): void;
  liveryCmpStats(id: number): string;
  liveryCmpForget(id: number): void;
  liveryCmpDispose(id: number): void;
}

let prepared: Promise<LiveryPlayerFactory> | undefined;
let activeViewports = 0;
// Each viewport owns a Skia/WebGL context. Leave room for other canvas users on a chat page.
const MAX_VIEWPORTS = 8;

/** Preload the optional Wasm/Skiko runtime and bundled font before rendering any components. */
export function prepareCmpPlayer(): Promise<LiveryPlayerFactory> {
  return prepared ??= prepare().catch((e) => { prepared = undefined; throw e; });
}

async function prepare(): Promise<LiveryPlayerFactory> {
  const base = new URL('./livery-web-compose/', import.meta.url);
  const url = new URL('livery-livery-web-compose.mjs', base).href;
  const font = async (name: string) => {
    const r = await fetch(new URL(`fonts/${name}`, base));
    if (!r.ok) throw new Error(`CMP font ${name}: HTTP ${r.status}`);
    return base64(new Uint8Array(await r.arrayBuffer()));
  };
  const [lib, roboto, symbols] = await Promise.all([
    import(/* @vite-ignore */ url) as Promise<CmpModule>,
    font('RobotoFlex.ttf'),
    font('NotoSansSymbols2-Regular.ttf'),
  ]);
  // Importing Skiko starts its fetch but does not await it. Finish it during preparation, too.
  const skikoUrl = new URL('skiko.mjs', base).href;
  await (await import(/* @vite-ignore */ skikoUrl)).awaitSkiko;
  // Compose's Locale parser rejects some OS tags (for example en-US@posix). Leave valid
  // browser settings untouched; repair only the invalid entries before composition starts.
  const languages = navigator.languages.map((tag) => tag.replaceAll('_', '-').split('@')[0]);
  const usable = languages.filter((tag) => { try { new Intl.Locale(tag); return true; } catch { return false; } });
  if (usable.join('\0') !== navigator.languages.join('\0')) {
    const safe = usable.length ? usable : ['en-US'];
    Object.defineProperty(navigator, 'languages', { configurable: true, get: () => safe });
    Object.defineProperty(navigator, 'language', { configurable: true, get: () => safe[0] });
  }
  lib.liveryCmpPrepare(roboto, symbols);
  return (box, onAction) => {
    if (activeViewports >= MAX_VIEWPORTS) return new RcCanvasPlayer(box, onAction);
    activeViewports++;
    return new CmpPlayer(lib, box, onAction, () => activeViewports--);
  };
}

class CmpPlayer implements LiveryPlayer {
  private id?: number;
  private key?: string;
  private latest = 0;
  private disposed = false;
  private size = '';
  private queue: Promise<unknown> = Promise.resolve();
  readonly stats = { loads: 0, sets: 0 };

  constructor(private readonly lib: CmpModule, private readonly box: HTMLElement, private readonly onAction: (action: NamedAction) => void, private readonly release: () => void) {}

  show(key: string, document: Uint8Array, values: Record<string, HostValue>, width: number, height: number, density: number): Promise<boolean> {
    const mine = ++this.latest;
    const run = this.queue.then(async () => {
      if (this.disposed || mine !== this.latest) return false;
      this.resize(width, height, density);
      // Mount only after the element's height rule has sized its container.
      this.id ??= this.lib.liveryCmpMount(this.box, (name, type, value) => {
        if (!this.disposed) this.onAction({ name, valueType: type as NamedAction['valueType'], value: type === 'int' || type === 'float' ? Number(value) : value });
      });
      const frame = this.lib.liveryCmpShow(this.id, key, key === this.key ? '' : base64(document), JSON.stringify(values), density);
      this.key = key;
      const drawn = await frame;
      if (this.disposed) return false;
      Object.assign(this.stats, JSON.parse(this.lib.liveryCmpStats(this.id)));
      return drawn && mine === this.latest;
    });
    this.queue = run.catch(() => {});
    return run;
  }

  resize(width: number, height: number, density: number): void {
    if (this.disposed) return;
    if (width > 0 && height > 0) {
      this.box.style.width = `${width}px`;
      this.box.style.height = `${height}px`;
      const size = `${width}/${height}/${density}`;
      if (size !== this.size) {
        this.size = size;
        // Compose 1.12's viewport observes window resize, not its container's ResizeObserver.
        window.dispatchEvent(new Event('resize'));
      }
    }
    if (this.id !== undefined) this.lib.liveryCmpResize(this.id, density);
  }

  forget(): void {
    this.latest++;
    this.key = undefined;
    if (this.id !== undefined) this.lib.liveryCmpForget(this.id);
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.latest++;
    try {
      if (this.id !== undefined) this.lib.liveryCmpDispose(this.id);
    } finally { this.release(); }
  }
}

function base64(bytes: Uint8Array): string {
  let s = '';
  for (let i = 0; i < bytes.length; i += 8192) s += String.fromCharCode(...bytes.subarray(i, i + 8192));
  return btoa(s);
}
