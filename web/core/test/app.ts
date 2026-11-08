import { ComponentPreview } from '../dist/index.js';
const previews = ['a', 'b'].map(id => new ComponentPreview(document.getElementById(id)!, './preview.json', { config: { unsigned: true }, examples: { Empty: { quantity: 0 }, Maximum: { quantity: 9 } } }));
Object.assign(window, { previews, ComponentPreview });
await Promise.all(previews.map(p => p.ready));
Object.assign(window, { previewReady: true });
