import { createElement, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { createComponentImplementation, type ReactA2uiComponentProps } from '@a2ui/react/v0_9';
import { basicCatalog } from '@a2ui/web_core/v0_9/basic_catalog';
import { componentApi, preparePlayerOptions, type LiveryCatalogOptions } from './api.js';
import type { LiverySession } from './livery.js';
import { hostStyle, LiveryController } from './controller.js';
export { LiverySession, loadLiveryWeb } from './livery.js';

export async function liveryReactCatalog(session: LiverySession, options: LiveryCatalogOptions) {
  options = await preparePlayerOptions(options);
  const components = (await session.components()).map((spec) => {
    const api = componentApi(session, spec, options);
    function View({ context, props }: ReactA2uiComponentProps<Record<string, unknown>>) {
      const host = useRef<HTMLDivElement>(null);
      const controller = useRef<LiveryController | null>(null);
      const [, update] = useState(0);
      useLayoutEffect(() => {
        const el = host.current!;
        el.style.cssText = hostStyle;
        const c = new LiveryController(el, () => update((n) => n + 1), () => new Promise(requestAnimationFrame));
        controller.current = c;
        (el.shadowRoot ?? el.attachShadow({ mode: 'open' })).append(c.box);
        c.connect();
        c.update(api, context, props);
        return () => { c.disconnect(); c.box.remove(); controller.current = null; };
      }, []);
      useLayoutEffect(() => { controller.current?.update(api, context, props); });
      const reason = controller.current?.fallback;
      // The player owns its shadow tree; React owns the optional fallback beside it.
      if (controller.current) controller.current.box.style.display = reason === undefined ? '' : 'none';
      return createElement('div', null,
        createElement('div', { ref: host, 'data-livery-component': spec.name }),
        reason === undefined ? null : options.fallback?.(spec, reason) as ReactNode);
    }
    return { ...createComponentImplementation(api, View), ...api };
  });
  const base = options.base ?? basicCatalog;
  return { catalog: base.derive({ catalogId: options.catalogId, components: [...base.components.keys(), ...components] as any }), components };
}
