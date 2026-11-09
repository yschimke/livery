// The parts of the TypeScript Remote Compose player (vendor/PROVENANCE.md) @livery/a2ui uses.
export type HostValue = number | string | number[];

export interface HostApi {
  variables(): Record<string, string>;
  get(name: string): HostValue | undefined;
  set(name: string, value: HostValue): boolean;
  onAction(name: string, handler: (value: unknown, name: string) => void): () => void;
  onChange(name: string, handler: (value: HostValue | undefined, name: string) => void): () => void;
  dispose(): void;
}

export interface RcdPlayer {
  setDensity(density: number): void;
  repaint(): void;
  measureSize(): { width: number; height: number } | null;
  resize(width: number, height: number): void;
  setTheme(theme: 'light' | 'dark' | 'auto'): void;
}

export interface RcPlayerHandle {
  loadFromArrayBuffer(buffer: ArrayBuffer): Promise<unknown>;
  resize(width: number, height: number): void;
  destroy(): void;
  player: RcdPlayer;
  canvas: HTMLCanvasElement;
}

export function createPlayer(container: HTMLElement, options?: { width?: number; height?: number; theme?: 'light' | 'dark' | 'auto'; background?: string }): RcPlayerHandle;
export function createHostApi(player: RcdPlayer): HostApi;
