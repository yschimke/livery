// The end-to-end page: A2UI's own engine and Lit renderer, the basic catalog plus Livery's
// components, a sample catalog served beside it, and rc-players' web player.
import '@a2ui/lit/v0_9';
import { MessageProcessor } from '@a2ui/web_core/v0_9';
import { LiverySession, liveryCatalog } from '../src/index.js';

const CATALOG_ID = 'https://livery.example/a2ui/catalog/sample-v1';
const actions: unknown[] = [];
const problems: [string, string][] = [];
const app: unknown[] = [];

const session = await LiverySession.open({ catalog: new URL('catalog/', location.href).href, unsigned: true, appKinds: ['navigate'] });
const { catalog, components } = await liveryCatalog(session, {
  catalogId: CATALOG_ID,
  onProblem: (c, m) => problems.push([c, m]),
  onAppAction: (kind, action, args) => app.push({ kind, action, args }),
});
const processor = new MessageProcessor([catalog as any], (a) => { actions.push(a); });
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

const surface = document.createElement('a2ui-surface') as any;
surface.surface = processor.model.getSurface('chat-1');
document.getElementById('app')!.append(surface);

// A second session and catalog on the same page, with the same component names: its components
// draw with their own session and report to their own processor.
const CATALOG_2 = 'https://livery.example/a2ui/catalog/second';
const actions2: unknown[] = [];
const session2 = await LiverySession.open({ catalog: new URL('catalog/', location.href).href, unsigned: true });
const second = await liveryCatalog(session2, { catalogId: CATALOG_2 });
const processor2 = new MessageProcessor([second.catalog as any], (a) => { actions2.push(a); });
processor2.processMessages([
  { version: 'v0.9', createSurface: { surfaceId: 'chat-2', catalogId: CATALOG_2 } },
  { version: 'v0.9', updateComponents: { surfaceId: 'chat-2', components: [
    { id: 'root', component: 'PromoBanner', headline: 'Second surface', cta: 'Go', actions: { cta: { event: { name: 'secondTrial' } } } },
  ] } },
] as any);
const surface2 = document.createElement('a2ui-surface') as any;
surface2.surface = processor2.model.getSurface('chat-2');
document.getElementById('second')!.append(surface2);

(window as any).__livery = {
  actions2,
  components: components.map((c) => c.name),
  actions, problems, app,
  data: (path: string) => (processor.model.getSurface('chat-1') as any).dataModel.get(path),
  send: (messages: unknown[]) => processor.processMessages(messages as any),
  events: () => session.events(),
};
