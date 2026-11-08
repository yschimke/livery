// The end-to-end page: A2UI's own engine and Lit renderer, the basic catalog plus Livery's
// components, a sample catalog served beside it, and rc-players' web player.
import { LiverySession } from '../dist/livery.js';
import { renderer } from './renderers.js';
const adapter = await renderer(new URLSearchParams(location.search).get('renderer') ?? 'lit');

const CATALOG_ID = 'https://livery.example/a2ui/catalog/sample-v1';
const actions: unknown[] = [];
const problems: [string, string][] = [];
const app: unknown[] = [];

const session = await LiverySession.open({ catalog: new URL('catalog/', location.href).href, unsigned: true, appKinds: ['navigate'] });
const { catalog, components } = await adapter.catalog(session, {
  catalogId: CATALOG_ID,
  onProblem: (c, m) => problems.push([c, m]),
  onAppAction: (kind, action, args) => app.push({ kind, action, args }),
});
const processor = await adapter.processor(catalog, (a: unknown) => { actions.push(a); });
processor.processMessages([
  { version: 'v0.9', createSurface: { surfaceId: 'chat-1', catalogId: CATALOG_ID, theme: { primaryColor: '#00897B' } } },
  { version: 'v0.9', updateDataModel: { surfaceId: 'chat-1', path: '/', value: { offer: { title: 'Spring sale' }, cart: { quantity: 1 } } } },
  {
    version: 'v0.9',
    updateComponents: {
      surfaceId: 'chat-1',
      components: [
        { id: 'root', component: 'Column', children: ['intro', 'promo', 'qty'] },
        { id: 'intro', component: 'Text', text: 'Livery components in an A2UI surface' },
        { id: 'promo', component: 'PromoBanner', headline: { path: '/offer/title' }, cta: 'Shop now', actions: { cta: { event: { name: 'startTrial', context: { from: 'chat' } } } } },
        { id: 'qty', component: 'ShopQuantity', name: 'Socks', price: '£4', quantity: { path: '/cart/quantity' } },
      ],
    },
  },
] as any);

adapter.mount(processor, 'chat-1', document.getElementById('app')!);

// A second session and catalog on the same page, with the same component names: its components
// draw with their own session and report to their own processor.
const CATALOG_2 = 'https://livery.example/a2ui/catalog/second';
const actions2: unknown[] = [];
const session2 = await LiverySession.open({ catalog: new URL('catalog/', location.href).href, unsigned: true });
const second = await adapter.catalog(session2, { catalogId: CATALOG_2 });
const processor2 = await adapter.processor(second.catalog, (a: unknown) => { actions2.push(a); });
processor2.processMessages([
  { version: 'v0.9', createSurface: { surfaceId: 'chat-2', catalogId: CATALOG_2 } },
  { version: 'v0.9', updateComponents: { surfaceId: 'chat-2', components: [
    { id: 'root', component: 'PromoBanner', headline: 'Second surface', cta: 'Go', actions: { cta: { event: { name: 'secondTrial' } } } },
  ] } },
] as any);
adapter.mount(processor2, 'chat-2', document.getElementById('second')!);

(window as any).__livery = {
  cachedComponents: async (options: Record<string, unknown>) => { try { return (await (await LiverySession.open({
    catalog: new URL('catalog/', location.href).href, unsigned: true, appKinds: ['navigate'], ...options,
  })).components()).map((c) => c.name); } catch { return null; } },
  actions2,
  components: components.map((c) => c.name),
  actions, problems, app,
  data: (path: string) => (processor.model.getSurface('chat-1') as any).dataModel.get(path),
  send: (messages: unknown[]) => processor.processMessages(messages as any),
  events: () => session.events(),
};
