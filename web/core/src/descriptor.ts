/** Experimental sample descriptor, not a published protocol. Trust is configured by the host. */
export interface PreviewDescriptor {
  schemaVersion: 1; mode: 'preview'; publicationId: string; revision: string; canonicalUrl: string;
  catalog: { manifestUrl: string };
  template: { contract: string; designId: string; sha256: string };
  data: Record<string, unknown>;
  fallback: { title: string; description: string };
}
function record(value: unknown): value is Record<string, unknown> { return !!value && typeof value === 'object' && !Array.isArray(value); }
/** The prototype permits plaintext transport only for its loopback development server. */
export function previewUrl(source: string): URL {
  const url = new URL(source);
  const local = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname);
  if (url.username || url.password || (url.protocol !== 'https:' && !(url.protocol === 'http:' && local))) throw new Error('Previews require HTTPS (or loopback development)');
  return url;
}
export function descriptor(value: unknown, source: string): PreviewDescriptor {
  if (!record(value) || value.schemaVersion !== 1 || value.mode !== 'preview') throw new Error('Unsupported preview format');
  if (!record(value.catalog) || !record(value.template) || !record(value.data) || !record(value.fallback)) throw new Error('Incomplete preview');
  for (const s of [value.publicationId, value.revision, value.canonicalUrl, value.catalog.manifestUrl, value.template.contract, value.template.designId, value.fallback.title, value.fallback.description]) {
    if (typeof s !== 'string' || !s.length) throw new Error('Invalid preview field');
  }
  if (typeof value.template.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(value.template.sha256)) throw new Error('Invalid template hash');
  const base = previewUrl(source);
  const resolve = (v: unknown) => {
    const url = new URL(String(v), base);
    if (!['http:', 'https:'].includes(url.protocol) || url.origin !== base.origin || url.username || url.password) throw new Error('Preview URLs must use the host origin');
    return url.href;
  };
  const canonicalUrl = resolve(value.canonicalUrl), manifestUrl = resolve(value.catalog.manifestUrl);
  if (!new URL(manifestUrl).pathname.endsWith('/manifest.json')) throw new Error('Expected a catalog manifest');
  return { ...value, canonicalUrl, catalog: { manifestUrl } } as unknown as PreviewDescriptor;
}
