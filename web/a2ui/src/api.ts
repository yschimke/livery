import type { LiveryPlayerFactory } from './player.js';
import { prepareCmpPlayer } from './cmp-player.js';
import { ActionSchema, DynamicBooleanSchema, DynamicNumberSchema, DynamicStringSchema, DynamicValueSchema, type Catalog } from '@a2ui/web_core/v0_9';
import { z } from 'zod';
import type { LiveryComponentSpec, LiveryPropertySpec, LiverySession } from './livery.js';

export interface LiveryCatalogOptions {
  /** In-page player; optional CMP is prepared before any framework registers its catalog. */
  player?: 'ts' | 'cmp' | LiveryPlayerFactory;
  /** The catalog id the client advertises: basic components plus one per Livery contract. */
  catalogId: string;
  /** The catalog to extend; A2UI's basic catalog by default. */
  base?: Catalog<any, any>;
  /** An action of a kind the page handles (`navigate`, `dismiss`, `app:…`) that the agent didn't wire. */
  onAppAction?: (kind: string, action: string, args: Record<string, string>, component: string) => void;
  /**
   * Something the agent should hear about: data that doesn't bind, no playable template, a `set` on
   * an input it didn't bind to a path. The page tells its agent (A2UI's `error` message).
   */
  onProblem?: (component: string, message: string) => void;
  /** What draws when there is no template (the agent is told through [onProblem]); nothing by default. */
  fallback?: (spec: LiveryComponentSpec, reason: string) => unknown;
}

/** One Livery component's A2UI API: its name, tag, element and props schema, and the session it draws with. */
export interface LiveryComponentApi {
  name: string;
  tagName: string;
  schema: z.ZodTypeAny;
  spec: LiveryComponentSpec;
  session: LiverySession;
  options: LiveryCatalogOptions;
}

/** The props schema, from the same projection as Kotlin's (`livery-a2ui`): A2UI's own dynamic types. */
export function schemaOf(spec: LiveryComponentSpec): z.ZodTypeAny {
  const shape: Record<string, z.ZodTypeAny> = {};
  for (const p of spec.properties) {
    const s = propertySchema(p);
    shape[p.key] = p.required ? s : s.optional();
  }
  return z.object(shape).describe(spec.description);
}

function propertySchema(p: LiveryPropertySpec): z.ZodTypeAny {
  switch (p.kind) {
    case 'DynamicString': return DynamicStringSchema;
    case 'DynamicNumber': return DynamicNumberSchema;
    case 'DynamicBoolean': return DynamicBooleanSchema;
    case 'DynamicValue': return DynamicValueSchema;
    case 'String': return z.string();
    case 'Action': return ActionSchema;
    case 'Actions': return z.object(Object.fromEntries((p.properties ?? []).map((a) => [a, ActionSchema.optional()]))).strict();
    default: return z.any();
  }
}

export function componentApi(session: LiverySession, spec: LiveryComponentSpec, options: LiveryCatalogOptions): LiveryComponentApi {
  if (options.player === 'cmp') throw new Error("Use an async catalog factory with player: 'cmp', or pass await prepareCmpPlayer() to liveryComponent");
  return { name: spec.name, tagName: `livery-${kebab(spec.name)}`, schema: schemaOf(spec), session, spec, options };
}

export function kebab(s: string): string {
  return s.replace(/([a-z0-9])([A-Z])/g, '$1-$2').replace(/[^A-Za-z0-9]+/g, '-').toLowerCase();
}

export async function preparePlayerOptions(options: LiveryCatalogOptions): Promise<LiveryCatalogOptions> {
  return options.player === 'cmp' ? { ...options, player: await prepareCmpPlayer() } : options;
}
