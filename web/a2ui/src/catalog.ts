import { A2uiLitElement, basicCatalog } from '@a2ui/lit/v0_9';
import type { Catalog } from '@a2ui/web_core/v0_9';
import { css, html, nothing, type PropertyValues } from 'lit';
import type { LiveryComponentSpec, LiverySession } from './livery.js';
import { componentApi, type LiveryComponentApi as SharedComponentApi, type LiveryCatalogOptions } from './api.js';
import { LiveryController } from './controller.js';
import type { NamedAction } from './player.js';
export type { LiveryCatalogOptions } from './api.js';
/** Lit retains its custom-element constructor alongside the renderer-neutral API. */
export interface LiveryComponentApi extends SharedComponentApi { element: CustomElementConstructor; }

export async function liveryCatalog(session: LiverySession, options: LiveryCatalogOptions): Promise<{ catalog: Catalog<any, any>; components: LiveryComponentApi[] }> {
  const base = options.base ?? basicCatalog;
  const components = (await session.components()).map((spec) => liveryComponent(session, spec, options));
  const catalog = base.derive({ catalogId: options.catalogId, components: [...base.components.keys(), ...components] as any });
  return { catalog, components };
}

/**
 * The A2UI component for one contract ([spec]). Its element is defined once per tag, and takes its
 * session, spec and options from the catalog entry it was rendered from, never from the first
 * catalog that defined the tag: two catalogs (or a session opened again) each draw with their own.
 */
export function liveryComponent(session: LiverySession, spec: LiveryComponentSpec, options: LiveryCatalogOptions): LiveryComponentApi {
  const api = componentApi(session, spec, options);
  const { tagName } = api;
  let element = customElements.get(tagName);
  if (!element) {
    // One constructor per tag (the registry allows no other), all of them the same element.
    element = class extends LiveryElement {};
    customElements.define(tagName, element);
  }
  return { ...api, element };
}

class LiveryElement extends A2uiLitElement {
  static styles = css`:host { display:block; width:100%; height:var(--livery-a2ui-height,var(--livery-template-height,200px)); }`;
  private readonly liveryController = new LiveryController(this, () => this.requestUpdate(), () => this.updateComplete);
  private get liveryApi(): LiveryComponentApi | undefined {
    const model = this.context?.componentModel;
    return model?.catalog?.components.get(model.type) as LiveryComponentApi | undefined;
  }
  get liveryStats() { return this.liveryController.liveryStats; }
  get liveryTheme() { return this.liveryController.liveryTheme; }
  liveryHostAction(action: NamedAction) { return this.liveryController.liveryHostAction(action); }
  connectedCallback() { super.connectedCallback(); this.liveryController.connect(); }
  disconnectedCallback() { this.liveryController.disconnect(); super.disconnectedCallback(); }
  protected updated(changed: PropertyValues) {
    super.updated(changed);
    if (this.liveryApi) this.dataset.liveryComponent = this.liveryApi.name;
    if (this.context) this.liveryController.update(this.liveryApi, this.context, this.controller.props ?? {});
  }
  render() {
    const reason = this.liveryController.fallback;
    return reason === undefined ? html`${this.liveryController.box}` : html`${this.liveryApi?.options.fallback?.(this.liveryApi.spec, reason) ?? nothing}`;
  }
}
