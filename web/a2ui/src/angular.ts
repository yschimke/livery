import { Component, ElementRef, effect, inject, type OnDestroy } from '@angular/core';
import { A2uiRendererService, AngularCatalog, CatalogComponent, createComponentImplementation, BasicCatalog } from '@a2ui/angular/v0_9';
import { ComponentContext } from '@a2ui/web_core/v0_9';
import { componentApi, preparePlayerOptions, type LiveryComponentApi, type LiveryCatalogOptions } from './api.js';
import type { LiverySession } from './livery.js';
import { hostStyle, LiveryController } from './controller.js';
export { LiverySession, loadLiveryWeb } from './livery.js';

@Component({ selector: 'livery-angular-view', standalone: true, template: '' })
export class LiveryAngularComponent extends CatalogComponent<LiveryComponentApi> implements OnDestroy {
  private readonly host = inject(ElementRef<HTMLElement>).nativeElement;
  private readonly service = inject(A2uiRendererService);
  private api?: LiveryComponentApi;
  private readonly fallbackBox = document.createElement('div');
  private readonly controller = new LiveryController(this.host, () => {
    const reason = this.controller.fallback;
    this.controller.box.style.display = reason === undefined ? '' : 'none';
    const content = reason === undefined ? '' : this.api?.options.fallback?.(this.api.spec, reason) ?? '';
    this.fallbackBox.replaceChildren(content instanceof Node ? content : String(content));
  }, () => new Promise(requestAnimationFrame));
  constructor() {
    super();
    this.host.style.cssText = hostStyle;
    this.host.attachShadow({ mode: 'open' }).append(this.controller.box, this.fallbackBox);
    this.controller.connect();
    effect(() => {
      const surface = this.service.surfaceGroup.getSurface(this.surfaceId());
      if (!surface) return;
      const context = new ComponentContext(surface, this.componentId(), this.dataContextPath());
      this.api = context.componentModel.catalog.components.get(context.componentModel.type) as unknown as LiveryComponentApi;
      this.host.dataset.liveryComponent = this.api.name;
      const props = Object.fromEntries(Object.entries(this.props()).map(([key, binding]) => [key, binding.value()]));
      this.controller.update(this.api, context, props);
    });
  }
  ngOnDestroy() { this.controller.disconnect(); }
}

export async function liveryAngularCatalog(session: LiverySession, options: LiveryCatalogOptions) {
  options = await preparePlayerOptions(options);
  const components = (await session.components()).map((spec) => {
    const api = componentApi(session, spec, options);
    return { ...api, ...createComponentImplementation(api, LiveryAngularComponent) };
  });
  const base = options.base ?? new BasicCatalog();
  const catalog = new AngularCatalog(options.catalogId, '0.9', [...base.components.values(), ...components] as any, [...base.functions.values()], base.themeSchema);
  return { catalog, components };
}
