import { mountPublication, type PublicationViewer } from './publication.js';

const viewers: PublicationViewer[] = [];
for (const element of document.querySelectorAll<HTMLElement>('[data-livery-preview]')) {
  try { viewers.push(mountPublication(element)); }
  catch (error) {
    element.dataset.state = 'fallback';
    const message = document.createElement('p'); message.setAttribute('role', 'status');
    message.textContent = `Interactive preview unavailable — ${error instanceof Error ? error.message : String(error)}`;
    element.replaceChildren(message);
  }
}
// Browsers retain the document in the back/forward cache; its visibility handler pauses playback.
window.addEventListener('pagehide', event => { if (!event.persisted) viewers.forEach(viewer => viewer.dispose()); });
export { mountPublication } from './publication.js';
