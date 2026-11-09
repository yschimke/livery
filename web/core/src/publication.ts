import { ComponentPreview } from './preview.js';
import { previewUrl } from './descriptor.js';
import type { WebConfig } from './session.js';

export interface PublicationViewer {
  preview: ComponentPreview;
  dispose(): void;
}
const mounted = new WeakMap<HTMLElement, PublicationViewer>();

/** Enhances a server-rendered publication. Configuration belongs to the host, not the publication. */
export function mountPublication(element: HTMLElement): PublicationViewer {
  const existing = mounted.get(element);
  if (existing) return existing;
  const localUrl = (value: string | undefined, label: string) => {
    if (!value) throw new Error(`Missing ${label}`);
    const url = previewUrl(new URL(value, document.baseURI).href);
    if (url.origin !== location.origin) throw new Error(`${label} must use the host origin`);
    return url.href;
  };
  const source = localUrl(element.dataset.descriptor ?? document.querySelector<HTMLLinkElement>('link[rel="alternate"][type="application/vnd.livery.preview+json"]')?.href, 'preview descriptor');
  const canonical = localUrl(element.dataset.canonical ?? document.querySelector<HTMLLinkElement>('link[rel="canonical"]')?.href, 'canonical URL');
  const config = JSON.parse(element.dataset.config ?? '{}') as Omit<WebConfig, 'catalog'>;
  if (!config || typeof config !== 'object' || Array.isArray(config)) throw new Error('Invalid host configuration');
  const mode = element.dataset.mode ?? 'page';
  if (mode !== 'page' && mode !== 'embed') throw new Error('Unsupported viewer mode');
  const body = document.createElement('div');
  body.className = 'livery-publication-component';
  element.classList.add('livery-publication');
  element.replaceChildren(body);
  const preview = new ComponentPreview(body, source, { config, controls: mode === 'embed' ? 'minimal' : 'full', showActionLog: false });
  const share = document.createElement('nav');
  share.className = 'livery-publication-share';
  share.setAttribute('aria-label', mode === 'embed' ? 'Full preview' : 'Share preview');
  const status = document.createElement('p');
  status.className = 'livery-share-status';
  status.setAttribute('role', 'status');
  let disposed = false;
  const abort = new AbortController();
  const say = (text: string) => { if (!disposed) status.textContent = text; };
  if (mode === 'embed') {
    const open = document.createElement('a');
    open.textContent = 'Open full preview'; open.href = canonical; open.target = '_blank'; open.rel = 'noopener'; share.append(open);
  } else {
    const bluesky = document.createElement('a');
    bluesky.textContent = 'Share to Bluesky';
    bluesky.href = `https://bsky.app/intent/compose?${new URLSearchParams({ text: canonical })}`;
    bluesky.target = '_blank'; bluesky.rel = 'noopener noreferrer';
    share.append(bluesky);
    if (typeof navigator.share === 'function') {
      const system = document.createElement('button'); system.type = 'button'; system.textContent = 'Share…';
      system.addEventListener('click', () => {
        // Invoke inside this click, before any await: transient activation must reach the OS sheet.
        void navigator.share({ title: document.title, url: canonical }).then(() => say('Shared'), error => {
          say(error instanceof DOMException && error.name === 'AbortError' ? 'Share cancelled' : 'Sharing unavailable. Use Copy link instead.');
        });
      }, { signal: abort.signal });
      share.append(system);
    }
    const copy = document.createElement('button'); copy.type = 'button'; copy.textContent = 'Copy link';
    const manual = document.createElement('input'); manual.readOnly = true; manual.value = canonical; manual.hidden = true; manual.setAttribute('aria-label', 'Preview link');
    copy.addEventListener('click', () => {
      const failed = () => { if (!disposed) { manual.hidden = false; manual.focus(); manual.select(); say('Select and copy the preview link.'); } };
      if (!navigator.clipboard?.writeText) { failed(); return; }
      void navigator.clipboard.writeText(canonical).then(() => say('Link copied'), failed);
    }, { signal: abort.signal });
    share.append(copy, manual);
  }
  element.append(share, status);
  const viewer = { preview, dispose() {
    if (disposed) return;
    disposed = true; abort.abort(); preview.dispose(); share.remove(); status.remove(); mounted.delete(element);
  } };
  mounted.set(element, viewer);
  return viewer;
}
