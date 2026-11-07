/**
 * rc-players' Wasm Remote Compose player in an iframe, over its documented `postMessage`
 * contract (RC_PLAYER_EMBED.md): hello until it answers, then `rc-player-load` with bytes; it
 * reports `rc-player-state` and each host named action. The frame is kept across loads, so a new
 * document is a swap, not a fresh Wasm module.
 */
export interface NamedAction {
  name: string;
  valueType: 'none' | 'string' | 'int' | 'float' | 'float-array';
  value: unknown;
}

export class RcFramePlayer {
  readonly frame: HTMLIFrameElement;
  private hello?: ReturnType<typeof setInterval>;
  private ready: Promise<void>;
  private resolveReady!: () => void;
  private nextId = 1;
  private pending = new Map<string, { resolve: () => void; reject: (e: Error) => void }>();
  private readonly onMessage = (e: MessageEvent) => this.message(e);

  constructor(playerUrl: string, private readonly onAction: (a: NamedAction) => void) {
    this.frame = document.createElement('iframe');
    this.frame.title = 'Livery template';
    this.frame.style.border = '0';
    this.frame.style.width = '100%';
    this.frame.style.height = '100%';
    this.frame.style.display = 'block';
    this.frame.src = playerUrl;
    this.ready = new Promise((resolve) => (this.resolveReady = resolve));
    window.addEventListener('message', this.onMessage);
    // The player listens only once its Wasm module runs: say hello until it answers.
    this.hello = setInterval(() => this.post({ type: 'rc-player-hello', id: 'livery-hello' }), 100);
  }

  /** Plays [bytes]; resolves when the document is on screen. Last one wins: a superseded load never resolves. */
  async load(bytes: Uint8Array): Promise<void> {
    await this.ready;
    const id = `livery-${this.nextId++}`;
    const done = new Promise<void>((resolve, reject) => this.pending.set(id, { resolve, reject }));
    // A copy, transferred: the caller keeps its own bytes.
    const copy = bytes.slice().buffer;
    this.frame.contentWindow?.postMessage({ type: 'rc-player-load', id, bytes: copy }, '*', [copy]);
    return done;
  }

  dispose(): void {
    if (this.hello) clearInterval(this.hello);
    window.removeEventListener('message', this.onMessage);
    this.pending.clear();
  }

  private post(message: unknown) {
    this.frame.contentWindow?.postMessage(message, '*');
  }

  private message(e: MessageEvent) {
    if (e.source !== this.frame.contentWindow) return;
    const m = e.data as Record<string, unknown> | string;
    if (typeof m !== 'object' || m === null) return;
    if (m.type === 'rc-player-hello') {
      if (this.hello) clearInterval(this.hello);
      this.hello = undefined;
      this.resolveReady();
    } else if (m.type === 'rc-player-state' && typeof m.id === 'string') {
      const p = this.pending.get(m.id);
      if (!p) return;
      this.pending.delete(m.id);
      if (m.state === 'ready') p.resolve();
      else p.reject(new Error(String(m.error ?? 'the player could not play the document')));
    } else if (m.type === 'cp-rc-host-named-action' && typeof m.name === 'string') {
      this.onAction({ name: m.name, valueType: m.valueType as NamedAction['valueType'], value: m.value });
    }
  }
}
