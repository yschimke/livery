// Each run loads exactly one renderer (Angular installs its own signal implementation).
export async function renderer(name: string) {
  if (name === 'react') {
    const { createElement } = await import('react');
    const { createRoot } = await import('react-dom/client');
    const { A2uiSurface } = await import('@a2ui/react/v0_9');
    const { MessageProcessor } = await import('@a2ui/web_core/v0_9');
    const { liveryReactCatalog } = await import('../dist/react.js');
    return {
      catalog: liveryReactCatalog,
      processor: async (catalog: any, action: any) => new MessageProcessor([catalog], action),
      mount: (p: any, id: string, el: HTMLElement) => createRoot(el).render(createElement(A2uiSurface, { surface: p.model.getSurface(id) })),
    };
  }
  if (name === 'angular') {
    await import('@angular/compiler');
    const { createApplication } = await import('@angular/platform-browser');
    const { createComponent } = await import('@angular/core');
    const { provideA2Ui, provideMarkdownRenderer, A2uiRendererService, SurfaceComponent } = await import('@a2ui/angular/v0_9');
    const { liveryAngularCatalog } = await import('../dist/angular.js');
    const apps = new Map();
    return {
      catalog: liveryAngularCatalog,
      processor: async (catalog: any, action: any) => {
        const app = await createApplication({ providers: [provideMarkdownRenderer(), provideA2Ui({ catalogs: [catalog], actionHandler: action })] });
        const p = app.injector.get(A2uiRendererService).processor;
        apps.set(p, app);
        return p;
      },
      mount: (p: any, id: string, el: HTMLElement) => {
        const app = apps.get(p);
        const view = createComponent(SurfaceComponent, { environmentInjector: app.injector, hostElement: el });
        view.setInput('surfaceId', id);
        app.attachView(view.hostView);
        app.tick();
      },
    };
  }
  const { MessageProcessor } = await import('@a2ui/web_core/v0_9');
  const { liveryCatalog } = await import('../dist/catalog.js');
  return {
    catalog: liveryCatalog,
    processor: async (catalog: any, action: any) => new MessageProcessor([catalog], action),
    mount: (p: any, id: string, el: HTMLElement) => {
      const surface = document.createElement('a2ui-surface') as any;
      surface.surface = p.model.getSurface(id); el.append(surface);
    },
  };
}
